package com.nightscout.android.upload;

import java.io.Serializable;

public class Record implements Serializable {
    public String displayTime = "---";

    public void setDisplayTime (String input) {
        this.displayTime = input;
    }

    private static final long serialVersionUID = 4654897646L;
    public String bGValue = "---";
    public String trend ="---";
    public String trendArrow = "---";

    public void setBGValue (String input) {
        this.bGValue = input;
    }

    public void setTrend (String input) {
        this.trend = input;
    }

    public void setTrendArrow (String input) {
        this.trendArrow = input;
    }
}
