/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.verilog.file;

import com.cburch.logisim.util.LocaleManager;
import com.cburch.logisim.util.StringGetter;
import com.cburch.logisim.util.StringUtil;

public class Strings {
	private static final LocaleManager source
		= new LocaleManager("resources/logisim", "verilog");

	public static String get(String key) {
		return source.get(key);
	}
    public static String get(String key, String... args) {
        return StringUtil.format(source.get(key), args);
    }
    public static String get(String key, String arg1, String arg2) {
        return StringUtil.format(source.get(key), arg1, arg2);
    }
    public static String get(String key, String arg1, String arg2, String arg3) {
        return StringUtil.format(source.get(key), arg1, arg2, arg3);
    }
    public static String get(String key, int n) {
        return StringUtil.format(source.get(key), String.valueOf(n));
    }
	public static StringGetter getter(String key) {
		return source.getter(key);
	}
}
