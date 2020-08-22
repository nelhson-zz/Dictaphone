package org.borisveriga.soundrecorder.util;

import android.app.Activity;
import android.view.WindowManager;

public class ScreenLock {

    public static void keepScreenOn(Activity activity) {
        if (activity != null) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    public static void allowScreenTurnOff(Activity activity) {
        if (activity != null) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

}
