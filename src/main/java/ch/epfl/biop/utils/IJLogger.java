package ch.epfl.biop.utils;

import ij.IJ;

public class IJLogger {
    public static void info(String title, String message){ IJ.log(Tools.getCurrentDateAndHour() + "   [INFO]             ["+title+"] -- "+message);  }
    public static void warn(String title, String message){ IJ.log(Tools.getCurrentDateAndHour() + "   [WARNING]    ["+title+"] -- "+message);  }
    public static void error(String title, String message){ IJ.log(Tools.getCurrentDateAndHour() + "   [ERROR]        ["+title+"] -- "+message); }
    public static void error(String title, String message, Exception e){
        error(title, message);
        error(e.toString(), "\n"+Tools.getErrorStackTraceAsString(e));
    }
    public static void info(String message){ IJ.log(Tools.getCurrentDateAndHour() + "   [INFO]             "+message); }
    public static void warn(String message){ IJ.log(Tools.getCurrentDateAndHour() + "   [WARNING]    "+message);}
    public static void error(String message){ IJ.log(Tools.getCurrentDateAndHour() + "   [ERROR]        "+message);  }
    public static void error(String message, Exception e){
        error(message);
        error(e.toString(), "\n"+Tools.getErrorStackTraceAsString(e));
    }
}
