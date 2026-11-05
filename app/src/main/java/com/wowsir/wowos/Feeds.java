package com.wowsir.wowos;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses public RSS/Atom feeds (news, sports, tech) and YouTube video
 * feeds (trailers) into a simple list of items with a title, a link and a thumbnail.
 * Everything here is from feeds meant to be consumed this way — no scraping of any
 * streaming service's private catalogue. Results are cached briefly in memory.
 */
final class Feeds {

    private Feeds() { }

    static final class Item {
        String title;
        String link;
        String image;
        boolean video;
    }

    private static final Pattern IMG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final long TTL_MS = 15 * 60_000L;

    private static final Map<String, long[]> STAMP = new HashMap<>();
    private static final Map<String, List<Item>> CACHE = new HashMap<>();

    /** Fetch and merge several feeds, newest-source-first, capped. Cached for 15 min. */
    static synchronized List<Item> fetchMerged(String cacheKey, String[] urls, int cap) {
        long now = System.currentTimeMillis();
        long[] s = STAMP.get(cacheKey);
        if (s != null && now - s[0] < TTL_MS && CACHE.get(cacheKey) != null) return CACHE.get(cacheKey);
        List<Item> all = new ArrayList<>();
        for (String u : urls) {
            try {
                List<Item> part = fetchOne(u);
                for (Item it : part) { all.add(it); if (all.size() >= cap) break; }
            } catch (Throwable ignored) { }
            if (all.size() >= cap) break;
        }
        STAMP.put(cacheKey, new long[]{now});
        CACHE.put(cacheKey, all);
        return all;
    }

    private static List<Item> fetchOne(String urlStr) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "WOWOS-Launcher");
            c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml");
            in = c.getInputStream();
            return parse(in);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static List<Item> parse(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);

        List<Item> items = new ArrayList<>();
        Item cur = null;
        String text = null;
        String videoId = null;

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            if (ev == XmlPullParser.START_TAG) {
                if (eq(name, "item") || eq(name, "entry")) { cur = new Item(); videoId = null; }
                else if (cur != null) {
                    if (eq(name, "media:thumbnail") || eq(name, "media:content") || eq(name, "thumbnail")) {
                        String u = p.getAttributeValue(null, "url");
                        if (u != null && cur.image == null && looksImage(u)) cur.image = u;
                    } else if (eq(name, "enclosure")) {
                        String u = p.getAttributeValue(null, "url");
                        String t = p.getAttributeValue(null, "type");
                        if (u != null && cur.image == null && (t == null || t.startsWith("image"))) cur.image = u;
                    } else if (eq(name, "link")) {
                        String href = p.getAttributeValue(null, "href");   // Atom
                        if (href != null && cur.link == null) cur.link = href;
                    }
                }
                text = null;
            } else if (ev == XmlPullParser.TEXT) {
                text = text == null ? p.getText() : text + p.getText();
            } else if (ev == XmlPullParser.END_TAG) {
                if (cur != null) {
                    if (eq(name, "title") && cur.title == null) cur.title = clean(text);
                    else if (eq(name, "link") && cur.link == null && text != null && text.trim().startsWith("http")) cur.link = text.trim();
                    else if (eq(name, "yt:videoId")) { videoId = text == null ? null : text.trim(); cur.video = true; }
                    else if ((eq(name, "description") || eq(name, "content:encoded") || eq(name, "summary")) && cur.image == null && text != null) {
                        Matcher m = IMG.matcher(text);
                        if (m.find()) cur.image = m.group(1);
                    } else if (eq(name, "item") || eq(name, "entry")) {
                        if (videoId != null) {
                            if (cur.link == null) cur.link = "https://www.youtube.com/watch?v=" + videoId;
                            if (cur.image == null) cur.image = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                        }
                        if (cur.title != null && cur.link != null) items.add(cur);
                        cur = null;
                    }
                }
                text = null;
            }
            ev = p.next();
        }
        return items;
    }

    private static boolean eq(String a, String b) { return a != null && a.equalsIgnoreCase(b); }

    private static boolean looksImage(String u) {
        String s = u.toLowerCase();
        if (s.contains(".jpg") || s.contains(".jpeg") || s.contains(".png") || s.contains(".webp")) return true;
        return !(s.contains(".mp4") || s.contains(".m3u8") || s.contains("video"));
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("<![CDATA[")) s = s.substring(9);
        if (s.endsWith("]]>")) s = s.substring(0, s.length() - 3);
        return s.trim();
    }
}
