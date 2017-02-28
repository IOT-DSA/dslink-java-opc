package opc;

import java.util.HashMap;
import java.util.Map;

public class Utils {
	
	public static final Map<Short, String> qualityCodes = new HashMap<Short, String>();
	
	static {
		qualityCodes.put((short)0, "Bad [Non-Specific]");
		qualityCodes.put((short)4, "Bad [Configuration Error]");
		qualityCodes.put((short)8, "Bad [Not Connected]");
		qualityCodes.put((short)12, "Bad [Device Failure]");
		qualityCodes.put((short)16, "Bad [Sensor Failure]");
		qualityCodes.put((short)20, "Bad [Last Known Value]");
		qualityCodes.put((short)24, "Bad [Communication Failure]");
		qualityCodes.put((short)28, "Bad [Out of Service]");
		qualityCodes.put((short)64, "Uncertain [Non-Specific]");
		qualityCodes.put((short)65, "Uncertain [Non-Specific] (Low Limited)");
		qualityCodes.put((short)66, "Uncertain [Non-Specific] (High Limited)");
		qualityCodes.put((short)67, "Uncertain [Non-Specific] (Constant)");
		qualityCodes.put((short)68, "Uncertain [Last Usable]");
		qualityCodes.put((short)69, "Uncertain [Last Usable] (Low Limited)");
		qualityCodes.put((short)70, "Uncertain [Last Usable] (High Limited)");
		qualityCodes.put((short)71, "Uncertain [Last Usable] (Constant)");
		qualityCodes.put((short)80, "Uncertain [Sensor Not Accurate]");
		qualityCodes.put((short)81, "Uncertain [Sensor Not Accurate] (Low Limited)");
		qualityCodes.put((short)82, "Uncertain [Sensor Not Accurate] (High Limited)");
		qualityCodes.put((short)83, "Uncertain [Sensor Not Accurate] (Constant)");
		qualityCodes.put((short)84, "Uncertain [EU Exceeded]");
		qualityCodes.put((short)85, "Uncertain [EU Exceeded] (Low Limited)");
		qualityCodes.put((short)86, "Uncertain [EU Exceeded] (High Limited)");
		qualityCodes.put((short)87, "Uncertain [EU Exceeded] (Constant)");
		qualityCodes.put((short)88, "Uncertain [Sub-Normal]");
		qualityCodes.put((short)89, "Uncertain [Sub-Normal] (Low Limited)");
		qualityCodes.put((short)90, "Uncertain [Sub-Normal] (High Limited)");
		qualityCodes.put((short)91, "Uncertain [Sub-Normal] (Constant)");
		qualityCodes.put((short)192, "Good [Non-Specific]");
		qualityCodes.put((short)193, "Good [Non-Specific] (Low Limited)");
		qualityCodes.put((short)194, "Good [Non-Specific] (High Limited)");
		qualityCodes.put((short)195, "Good [Non-Specific] (Constant)");
		qualityCodes.put((short)216, "Good [Local Override]");
		qualityCodes.put((short)217, "Good [Local Override] (Low Limited)");
		qualityCodes.put((short)218, "Good [Local Override] (High Limited)");
		qualityCodes.put((short)219, "Good [Local Override] (Constant)");
	}

}
