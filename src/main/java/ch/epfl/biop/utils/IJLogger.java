package ch.epfl.biop.utils;

import ij.IJ;

public class IJLogger {

    public static void info(String title, String message){ IJ.log("[INFO]  ["+title+"] -- "+message); }
    public static void warn(String title, String message){ IJ.log("[WARNING]  ["+title+"] -- "+message); }
    public static void error(String title, String message){ IJ.log("[ERROR]  ["+title+"] -- "+message); }
    public static void debug(String title, String message){ IJ.log("[DEBUG]  ["+title+"] -- "+message); }

    public static void info(String message){ IJ.log("[INFO] -- "+message); }
    public static void warn(String message){ IJ.log("[WARNING] -- "+message); }
    public static void error(String message){ IJ.log("[ERROR] -- "+message); }
    public static void debug(String message){ IJ.log("[DEBUG] -- "+message); }
}
