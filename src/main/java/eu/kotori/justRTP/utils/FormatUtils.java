package eu.kotori.justRTP.utils;

public class FormatUtils {

    public static String formatCost(double cost) {

        if (cost == Math.floor(cost)) {
            return String.format("%.0f", cost);
        }

        else if (cost * 2 == Math.floor(cost * 2)) {
            return String.format("%.1f", cost);
        }

        else {
            return String.format("%.2f", cost);
        }
    }
}
