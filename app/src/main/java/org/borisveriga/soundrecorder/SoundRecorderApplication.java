package org.borisveriga.soundrecorder;

import android.app.Application;

import com.squareup.leakcanary.LeakCanary;


public class SoundRecorderApplication extends Application {

    private static SoundRecorderApplication singleton = null;

    public static SoundRecorderApplication getInstance() {
        if (singleton == null) {
            singleton = new SoundRecorderApplication();
        }
        return singleton;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        singleton = this;

        if (LeakCanary.isInAnalyzerProcess(this)) {
            return;
        }
        LeakCanary.install(this);
    }
}
