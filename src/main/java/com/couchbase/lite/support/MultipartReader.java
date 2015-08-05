package com.couchbase.lite.support;

import org.apache.http.util.ByteArrayBuffer;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

public class MultipartReader {

    private enum MultipartReaderState {
        kUninitialized,
        kAtStart,
        kInPrologue,
        kInBody,
        kInHeaders,
        kAtEnd,
        kFailed
    }

    private static final Charset utf8 = Charset.forName("UTF-8");
    private static final byte[] kCRLFCRLF = new String("\r\n\r\n").getBytes(utf8);
    private static final byte[] kEOM = new String("--").getBytes(utf8);

    private MultipartReaderState state = null;
    private ByteArrayBuffer buffer = null;
    private String contentType = null;
    private byte[] boundary = null;
    private byte[] boundaryWithoutLeadingCRLF = null;
    private MultipartReaderDelegate delegate = null;
    public Map<String, String> headers = null;

    public MultipartReader(String contentType, MultipartReaderDelegate delegate) {
        this.contentType = contentType;
        this.delegate = delegate;

        this.buffer = new ByteArrayBuffer(1024);
        this.state = MultipartReaderState.kAtStart;

        parseContentType();
    }

    public byte[] getBoundary() {
        return boundary;
    }

    public byte[] getBoundaryWithoutLeadingCRLF() {
        if (boundaryWithoutLeadingCRLF == null) {
            byte[] rawBoundary = getBoundary();
            boundaryWithoutLeadingCRLF = Arrays.copyOfRange(rawBoundary, 2, rawBoundary.length);
        }
        return boundaryWithoutLeadingCRLF;
    }

    public boolean finished() {
        return state == MultipartReaderState.kAtEnd;
    }

    private boolean memcmp(byte[] array1, byte[] array2, int len) {
        boolean equals = true;
        for (int i = 0; i < len; i++) {
            if (array1[i] != array2[i]) {
                equals = false;
            }
        }
        return equals;
    }

    public Range searchFor(byte[] pattern, int start) {
        KMPMatch searcher = new KMPMatch();
        int buffLen = Math.min(buffer.length(), buffer.buffer().length);
        int matchIndex = searcher.indexOf(buffer.buffer(), buffLen, pattern, start);
        if (matchIndex != -1) {
            return new Range(matchIndex, pattern.length);
        } else {
            return new Range(matchIndex, 0);
        }
    }

    public void parseHeaders(String headersStr) {

        headers = new HashMap<String, String>();
        if (headersStr != null && headersStr.length() > 0) {
            headersStr = headersStr.trim();
            StringTokenizer tokenizer = new StringTokenizer(headersStr, "\r\n");
            while (tokenizer.hasMoreTokens()) {
                String header = tokenizer.nextToken();

                if (!header.contains(":")) {
                    throw new IllegalArgumentException("Missing ':' in header line: " + header);
                }
                StringTokenizer headerTokenizer = new StringTokenizer(header, ":");
                String key = headerTokenizer.nextToken().trim();
                String value = headerTokenizer.nextToken().trim();
                headers.put(key, value);
            }
        }
    }

    private void deleteUpThrough(int location) {
        // int start = location + 1;  // start at the first byte after the location

        if (location <= 0) return;

        byte[] b = buffer.buffer();
        int len = buffer.length();

        int j = 0;
        int i = location;
        while (i < len) {
            b[j++] = b[i++];
        }
        buffer.setLength(j);
    }

    private void trimBuffer() {
        int bufLen = buffer.length();
        int boundaryLen = getBoundary().length;
        if (bufLen > boundaryLen) {
            // Leave enough bytes in _buffer that we can find an incomplete boundary string
            delegate.appendToPart(buffer.buffer(), 0, bufLen - boundaryLen);
            deleteUpThrough(bufLen - boundaryLen);
        }
    }

    public void appendData(byte[] data) {
        appendData(data, 0, data.length);
    }

    public void appendData(byte[] data, int off, int len) {

        if (buffer == null) {
            return;
        }
        if (len == 0) {
            return;
        }
        buffer.append(data, off, len);

        MultipartReaderState nextState;
        do {
            nextState = MultipartReaderState.kUninitialized;
            int bufLen = buffer.length();
            switch (state) {
                case kAtStart: {
                    // The entire message might start with a boundary without a leading CRLF.
                    byte[] boundaryWithoutLeadingCRLF = getBoundaryWithoutLeadingCRLF();
                    if (bufLen >= boundaryWithoutLeadingCRLF.length) {
                        if (memcmp(buffer.buffer(), boundaryWithoutLeadingCRLF, boundaryWithoutLeadingCRLF.length)) {
                            deleteUpThrough(boundaryWithoutLeadingCRLF.length);
                            nextState = MultipartReaderState.kInHeaders;
                        } else {
                            nextState = MultipartReaderState.kInPrologue;
                        }
                    }
                    break;
                }
                case kInPrologue:
                case kInBody: {
                    // Look for the next part boundary in the data we just added and the ending bytes of
                    // the previous data (in case the boundary string is split across calls)
                    if (bufLen < boundary.length) {
                        break;
                    }
                    int start = Math.max(0, bufLen - data.length - boundary.length);
                    Range r = searchFor(boundary, start);
                    if (r.getLength() > 0) {
                        if (state == MultipartReaderState.kInBody) {
                            delegate.appendToPart(buffer.buffer(), 0, r.getLocation());
                            delegate.finishedPart();
                        }
                        deleteUpThrough(r.getLocation() + r.getLength());
                        nextState = MultipartReaderState.kInHeaders;
                    } else {
                        trimBuffer();
                    }
                    break;
                }
                case kInHeaders: {
                    // First check for the end-of-message string ("--" after separator):
                    if (bufLen >= kEOM.length && memcmp(buffer.buffer(), kEOM, kEOM.length)) {
                        state = MultipartReaderState.kAtEnd;
                        close();
                        return;
                    }
                    // Otherwise look for two CRLFs that delimit the end of the headers:
                    Range r = searchFor(kCRLFCRLF, 0);
                    if (r.getLength() > 0) {
                        String headersString = new String(buffer.buffer(), 0, r.getLocation(), utf8);
                        parseHeaders(headersString);
                        deleteUpThrough(r.getLocation() + r.getLength());
                        delegate.startedPart(headers);
                        nextState = MultipartReaderState.kInBody;
                    }
                    break;
                }
                default: {
                    throw new IllegalStateException("Unexpected data after end of MIME body");
                }
            }

            if (nextState != MultipartReaderState.kUninitialized) {
                state = nextState;
            }

        } while (nextState != MultipartReaderState.kUninitialized && buffer.length() > 0);
    }

    private void close() {
        if (buffer != null) buffer.clear();
        buffer = null;
        boundary = null;
        boundaryWithoutLeadingCRLF = null;
    }

    private void parseContentType() {

        StringTokenizer tokenizer = new StringTokenizer(contentType, ";");
        boolean first = true;
        while (tokenizer.hasMoreTokens()) {
            String param = tokenizer.nextToken().trim();
            if (first == true) {
                if (!param.startsWith("multipart/")) {
                    throw new IllegalArgumentException(contentType + " does not start with multipart/");
                }
                first = false;
            } else {
                if (param.startsWith("boundary=")) {
                    String tempBoundary = param.substring(9);
                    if (tempBoundary.startsWith("\"")) {
                        if (tempBoundary.length() < 2 || !tempBoundary.endsWith("\"")) {
                            throw new IllegalArgumentException(contentType + " is not valid");
                        }
                        tempBoundary = tempBoundary.substring(1, tempBoundary.length() - 1);
                    }
                    if (tempBoundary.length() < 1) {
                        throw new IllegalArgumentException(contentType + " has zero-length boundary");
                    }
                    tempBoundary = String.format("\r\n--%s", tempBoundary);
                    boundary = tempBoundary.getBytes(Charset.forName("UTF-8"));
                    break;
                }
            }
        }
    }
}

/**
 * Knuth-Morris-Pratt Algorithm for Pattern Matching
 */
class KMPMatch {
    /**
     * Finds the first occurrence of the pattern in the text.
     */
    public int indexOf(byte[] data, int dataLength, byte[] pattern, int dataOffset) {

        int[] failure = computeFailure(pattern);

        int j = 0;
        if (data.length == 0)
            return -1;

        //final int dataLength = data.length;
        final int patternLength = pattern.length;

        for (int i = dataOffset; i < dataLength; i++) {
            while (j > 0 && pattern[j] != data[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == data[i]) {
                j++;
            }
            if (j == patternLength) {
                return i - patternLength + 1;
            }
        }

        return -1;
    }

    /**
     * Computes the failure function using a boot-strapping process,
     * where the pattern is matched against itself.
     */
    private int[] computeFailure(byte[] pattern) {
        int[] failure = new int[pattern.length];

        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j > 0 && pattern[j] != pattern[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == pattern[i]) {
                j++;
            }
            failure[i] = j;
        }
        return failure;
    }
}