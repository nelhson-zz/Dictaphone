package org.borisveriga.soundrecorder.util;

import android.os.Environment;

import java.io.File;

public class Paths {

    public static final String SOUND_RECORDER_FOLDER = "/SoundRecorder";

    public static String combine(String parent, String... children) {
        return combine(new File(parent), children);
    }

    public static String combine(File parent, String... children) {
        File path = parent;
        for (String child : children) {
            path = new File(path, child);
        }
        return path.toString();
    }

    public static boolean isExternalStorageWritable() {
        return (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()));
    }

    public static void createDirectory(File parent, String... children){
        String cacheFileString = combine(parent, children);
        File cacheFile = new File(cacheFileString);
        if(!cacheFile.exists()){
            cacheFile.mkdirs();
        }
    }

}
