package org.borisveriga.soundrecorder.util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;

@IntDef({Command.INVALID, Command.START, Command.PAUSE, Command.STOP})
@Retention(RetentionPolicy.SOURCE)
public @interface Command {
    int INVALID = -1;
    int START = 0;
    int PAUSE = 1;
    int STOP = 2;
}
