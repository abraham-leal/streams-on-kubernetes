package com.shapira.examples.streams.stockstats;

public class Constants {
    public static final String STOCK_TOPIC = "metric_measure";
    public static final String[] TICKERS = {"MMM", "ABT", "ABBV", "ACN", "ATVI", "AYI", "ADBE", "AAP", "AES", "AET"};
    public static final int MAX_PRICE_CHANGE = 5;
    public static final int START_PRICE = 5000;
    public static final int DELAY = 500; // sleep in ms between sending "asks"

}
