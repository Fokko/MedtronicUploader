package com.nightscout.android.upload;

import java.io.Serializable;

public abstract class Record implements Serializable {
    private static final long serialVersionUID = 4654897646L;

    public String displayTime = "---";
    public String bGValue = "---";

    public String trend = "---";
    public String trendArrow = "---";

    public void setDisplayTime(String input) {
        this.displayTime = input;
    }

    public void setBGValue(String input) {
        this.bGValue = input;
    }
}
