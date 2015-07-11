package com.nightscout.android.upload;

import java.io.Serializable;

public class Record implements Serializable {
    /**
     *
     */
    private static final long serialVersionUID = -1381174446348390503L;
    public String displayTime = "---";

    public void setDisplayTime(String input) {
        this.displayTime = input;
    }

}
