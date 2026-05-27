package com.example.clock2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Модель одного будильника.
 * Хранит время, название, дни повтора и умеет сериализовать себя в JSON.
 */
public class AlarmItem {

    public int     id;
    public int     hour;
    public int     minute;
    public String  toneUri    = "";
    public boolean enabled    = true;
    public String  label      = "";
    /** Calendar.DAY_OF_WEEK константы: MONDAY=2 … SUNDAY=1. Пустой = однократный. */
    public Set<Integer> repeatDays = new LinkedHashSet<>();

    // ─── JSON ────────────────────────────────────────────────────────────────

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id",      id);
        obj.put("hour",    hour);
        obj.put("minute",  minute);
        obj.put("toneUri", toneUri);
        obj.put("enabled", enabled);
        obj.put("label",   label);
        JSONArray days = new JSONArray();
        for (int day : repeatDays) days.put(day);
        obj.put("repeatDays", days);
        return obj;
    }

    public static AlarmItem fromJson(JSONObject obj) throws JSONException {
        AlarmItem item = new AlarmItem();
        item.id      = obj.getInt("id");
        item.hour    = obj.getInt("hour");
        item.minute  = obj.getInt("minute");
        item.toneUri = obj.optString("toneUri", "");
        item.enabled = obj.optBoolean("enabled", true);
        item.label   = obj.optString("label", "");
        JSONArray days = obj.optJSONArray("repeatDays");
        if (days != null) {
            for (int i = 0; i < days.length(); i++) {
                item.repeatDays.add(days.getInt(i));
            }
        }
        return item;
    }

    // ─── Логика времени ───────────────────────────────────────────────────────

    /**
     * Вычисляет ближайшее время срабатывания. Делегирует {@link AlarmActivity#nextTriggerMillis}
     * — единый источник истины и для AlarmScheduler, и для UI обратного отсчёта.
     */
    public long computeNextFireTime() {
        int[] days = new int[repeatDays.size()];
        int i = 0;
        for (int d : repeatDays) days[i++] = d;
        return AlarmActivity.nextTriggerMillis(hour, minute, days);
    }

    /** @return Set отсортированный в естественном порядке константы Calendar.DAY_OF_WEEK. */
    public Set<Integer> repeatDaysSorted() {
        return new TreeSet<>(repeatDays);
    }

    // ─── UI-хелперы ──────────────────────────────────────────────────────────

    /**
     * Строка с днями повтора на русском: "Без повтора", "Каждый день", "Пн, Ср, Пт"…
     */
    public String buildRepeatLabel() {
        if (repeatDays.isEmpty()) return "Без повтора";
        if (repeatDays.size() == 7) return "Каждый день";

        int[]    ordered = {
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        };
        String[] names   = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.length; i++) {
            if (repeatDays.contains(ordered[i])) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }
}
