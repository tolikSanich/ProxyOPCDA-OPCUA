package com.opcproxy.calc;

/** Статические функции, доступные в формулах как #abs(..), #min(..), #max(..), #round(..), #xor(..). */
public final class CalcFunctions {
    private CalcFunctions() {}

    public static double abs(double v)                { return Math.abs(v); }
    public static double min(double a, double b)      { return Math.min(a, b); }
    public static double max(double a, double b)      { return Math.max(a, b); }
    public static double round(double v)              { return Math.round(v); }
    public static double round(double v, int digits)  {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
    public static boolean xor(boolean a, boolean b)   { return a ^ b; }
}