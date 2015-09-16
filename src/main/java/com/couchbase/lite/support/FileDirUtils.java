/**
 * Original iOS version by  Jens Alfke
 * Ported to Android by Marty Schoch
 * <p/>
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.couchbase.lite.support;

import com.couchbase.lite.Database;
import com.couchbase.lite.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public class FileDirUtils {

    public static boolean removeItemIfExists(String path) {
        File f = new File(path);
        return f.delete() || !f.exists();
    }

    public static boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        return fileOrDirectory.delete() || !fileOrDirectory.exists();
    }

    public static boolean cleanDirectory(File dir) {
        if (!dir.isDirectory())
            return false;

        for (File file : dir.listFiles()) {
            if (!deleteRecursive(file))
                return false;
        }
        return true;
    }

    public static String getDatabaseNameFromPath(String path) {
        String fileName = new File(path).getName();
        int extensionPos = fileName.lastIndexOf(".");
        if (extensionPos < 0) {
            String message = "Unable to determine database name from path: " + path;
            Log.e(Database.TAG, message);
            throw new IllegalArgumentException(message);
        }
        return fileName.substring(0, extensionPos);
    }

    public static String getPathWithoutExt(String path) {
        int pos = path.lastIndexOf(".");
        return pos > 0 ? path.substring(0, pos) : path;
    }

    public static void copyFile(File sourceFile, File destFile) throws IOException {
        if (!destFile.exists()) {
            destFile.createNewFile();
        }

        FileChannel source = null;
        FileChannel destination = null;
        try {
            source = new FileInputStream(sourceFile).getChannel();
            destination = new FileOutputStream(destFile).getChannel();
            destination.transferFrom(source, 0, source.size());
        } finally {
            if (source != null)
                source.close();
            if (destination != null)
                destination.close();
        }
    }

    public static void copyFolder(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            //if directory not exists, create it
            if (!dest.exists()) {
                dest.mkdir();
            }
            //list all the directory contents
            String files[] = src.list();
            for (String file : files) {
                //construct the src and dest file structure
                File srcFile = new File(src, file);
                File destFile = new File(dest, file);
                //recursive copy
                copyFolder(srcFile, destFile);
            }
        } else {
            copyFile(src, dest);
        }
    }
}
