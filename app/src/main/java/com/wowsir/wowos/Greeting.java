package com.wowsir.wowos;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/** Time-aware greeting and rotating messages for the home screen. */
final class Greeting {

    static final String K_NAME = "name";
    static final String K_SHOW = "show";
    static final String K_BUILTIN = "builtin";
    static final String K_MINE = "mine";

    /** Original WOW OS lines on courage, light and lifting others. */
    private static final String[] ANY_TIME = {
            "You were made to shine, not to hide. Let your light be seen today.",
            "Your gifts are not an accident. Use them fully and generously.",
            "Don't shrink to keep others comfortable. The world needs all of you.",
            "When you live boldly, you quietly give courage to everyone watching.",
            "Your brilliance doesn't take light from anyone. It adds to it.",
            "Fear of your own greatness is still fear. Step past it.",
            "Every student you teach is learning from how brightly you live.",
            "There is light in you, and in everyone you meet. Help them find it.",
            "Small thinking never built anything great. Think bigger today.",
            "Courage is simply allowing yourself to be as capable as you are.",
            "Free yourself from fear, and your freedom will lift others too.",
            "You don't need permission to be excellent. Begin.",
    };
    private static final String[] MORNING = {
            "A fresh page. Write something great on it.",
            "Start strong. Today is another chance to shine.",
            "Rise with purpose. Someone is waiting for what only you can give.",
    };
    private static final String[] AFTERNOON = {
            "Halfway there. Keep your light steady.",
            "Progress, not perfection. One more good step.",
            "The work you do this afternoon is building your tomorrow.",
    };
    private static final String[] EVENING = {
            "Look back at what you did today. You showed up, and that matters.",
            "Slow down and enjoy the evening. You've earned it.",
            "Light shared is never lost. Who did you lift today?",
    };
    private static final String[] NIGHT = {
            "Rest is part of the work. Recharge your light.",
            "Let today go. Tomorrow needs your best.",
            "Even on dark nights, your light is still there.",
    };

    private static final Random random = new Random();

    private Greeting() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("greeting", Context.MODE_PRIVATE);
    }

    static String name(Context c) {
        return prefs(c).getString(K_NAME, c.getString(R.string.default_name));
    }

    static boolean show(Context c) { return prefs(c).getBoolean(K_SHOW, true); }
    static boolean builtin(Context c) { return prefs(c).getBoolean(K_BUILTIN, true); }

    static List<String> mine(Context c) {
        String raw = prefs(c).getString(K_MINE, "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static void setMine(Context c, List<String> lines) {
        prefs(c).edit().putString(K_MINE, TextUtils.join("\n", lines)).apply();
    }

    static String hello(Context c) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String part;
        if (h >= 5 && h < 12) part = "Good morning";
        else if (h >= 12 && h < 17) part = "Good afternoon";
        else if (h >= 17 && h < 22) part = "Good evening";
        else part = "Hello, night owl";
        String n = name(c).trim();
        return n.isEmpty() ? part : part + ", " + n;
    }

    /** A message suited to the time of day, avoiding an immediate repeat. */
    static String message(Context c, String previous) {
        List<String> pool = new ArrayList<>(mine(c));
        if (builtin(c) || pool.isEmpty()) {
            pool.addAll(Arrays.asList(ANY_TIME));
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String[] timed = h >= 5 && h < 12 ? MORNING : h >= 12 && h < 17 ? AFTERNOON : h >= 17 && h < 22 ? EVENING : NIGHT;
            // Weight time-of-day lines so they come up often.
            pool.addAll(Arrays.asList(timed));
            pool.addAll(Arrays.asList(timed));
        }
        if (pool.isEmpty()) return "";
        String pick = pool.get(random.nextInt(pool.size()));
        if (pool.size() > 1 && pick.equals(previous)) pick = pool.get(random.nextInt(pool.size()));
        return pick;
    }
}
