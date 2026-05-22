package com.example.smartwalletpro;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class WalletStorage {
    private static final String PREFS_NAME = "WALLET_NATIVE_DATA";
    private static final String KEY_WALLET_JSON = "wallet_json";
    private static final String KEY_UNSEEN_IMPORTED_SMS_COUNT = "unseen_imported_sms_count";
    private static final String CHANNEL_ID = "sms_imports";
    private static final int NOTIFICATION_ID = 2001;

    private WalletStorage() {
    }

    public static String getWalletJson(Context context) {
        return getPrefs(context).getString(KEY_WALLET_JSON, "");
    }

    public static void saveWalletJson(Context context, String walletJson) {
        if (walletJson == null || walletJson.trim().isEmpty()) return;
        getPrefs(context).edit().putString(KEY_WALLET_JSON, walletJson).apply();
    }

    public static int getUnseenImportedSmsCount(Context context) {
        return getPrefs(context).getInt(KEY_UNSEEN_IMPORTED_SMS_COUNT, 0);
    }

    public static void markImportedSmsSeen(Context context) {
        getPrefs(context).edit().putInt(KEY_UNSEEN_IMPORTED_SMS_COUNT, 0).apply();
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
    }

    public static boolean importSmsTransaction(Context context, JSONObject sms) {
        try {
            JSONObject wallet = getOrCreateWallet(context);
            JSONArray entries = wallet.optJSONArray("entries");
            if (entries == null) {
                entries = new JSONArray();
                wallet.put("entries", entries);
            }

            JSONArray accounts = wallet.optJSONArray("acc");
            if (accounts == null || accounts.length() == 0) {
                accounts = defaultAccounts();
                wallet.put("acc", accounts);
            }

            JSONArray categories = wallet.optJSONArray("cat");
            if (categories == null) {
                categories = defaultCategories();
                wallet.put("cat", categories);
            }

            String type = sms.optString("type", "");
            String category = sms.optString("category", "General");
            if ("General".equals(category) && !sms.optString("note", "").isEmpty()) {
                category = sms.optString("note", "General");
            }
            String account = chooseAccount(accounts, sms.optString("bankName", ""), sms.optString("raw", ""));
            double amount = sms.optDouble("amount", 0);
            String date = sms.optString("dateStr", "");

            if (amount <= 0 || type.isEmpty() || date.isEmpty()) return false;
            if (entryExists(entries, date, amount, type, category, account)) return false;

            long now = System.currentTimeMillis();
            JSONObject entry = new JSONObject();
            entry.put("id", now);
            entry.put("date", date);
            entry.put("account", account);
            entry.put("category", category);
            entry.put("note", sms.optString("note", ""));
            entry.put("amount", amount);
            entry.put("currency", "KRW");
            entry.put("typeVal", type);
            entry.put("beh", "inc".equals(type) ? 1 : -1);
            entry.put("toAccount", JSONObject.NULL);
            entry.put("source", "sms");
            entry.put("smsTs", sms.optLong("ts", now));
            entry.put("raw", sms.optString("raw", ""));

            entries.put(entry);
            if (!"General".equals(category) && !containsString(categories, category)) {
                categories.put(category);
            }

            saveWalletJson(context, wallet.toString());
            int unseen = getUnseenImportedSmsCount(context) + 1;
            getPrefs(context).edit().putInt(KEY_UNSEEN_IMPORTED_SMS_COUNT, unseen).apply();
            showSmsImportNotification(context, unseen);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject getOrCreateWallet(Context context) {
        try {
            String raw = getWalletJson(context);
            if (raw != null && !raw.trim().isEmpty()) {
                JSONObject wallet = new JSONObject(raw);
                ensureWalletDefaults(wallet);
                return wallet;
            }
        } catch (Exception ignored) {
        }

        JSONObject wallet = new JSONObject();
        ensureWalletDefaults(wallet);
        return wallet;
    }

    private static void ensureWalletDefaults(JSONObject wallet) {
        try {
            if (!wallet.has("entries")) wallet.put("entries", new JSONArray());
            if (!wallet.has("acc")) wallet.put("acc", defaultAccounts());
            if (!wallet.has("cat")) wallet.put("cat", defaultCategories());
            if (!wallet.has("typ")) wallet.put("typ", defaultTypes());
            if (!wallet.has("curr")) {
                JSONArray currencies = new JSONArray();
                currencies.put("KRW");
                currencies.put("MMK");
                currencies.put("USD");
                wallet.put("curr", currencies);
            }
            if (!wallet.has("budget")) {
                JSONObject budget = new JSONObject();
                budget.put("start", "");
                budget.put("end", "");
                budget.put("limit", 500000);
                budget.put("currency", "KRW");
                wallet.put("budget", budget);
            }
            if (!wallet.has("recurring")) wallet.put("recurring", new JSONArray());
            if (!wallet.has("budgetHistory")) wallet.put("budgetHistory", new JSONArray());
            if (!wallet.has("bankEntries")) wallet.put("bankEntries", new JSONArray());
        } catch (Exception ignored) {
        }
    }

    private static JSONArray defaultAccounts() {
        JSONArray accounts = new JSONArray();
        accounts.put("Hana Bank");
        accounts.put("Hana Savings");
        accounts.put("Hana Credit");
        accounts.put("JB Bank");
        accounts.put("KBZ Pay");
        accounts.put("Cash");
        return accounts;
    }

    private static JSONArray defaultCategories() {
        JSONArray categories = new JSONArray();
        categories.put("Food");
        categories.put("Transport");
        categories.put("Shopping");
        categories.put("Rent");
        categories.put("Bills");
        categories.put("Health");
        categories.put("Entertainment");
        categories.put("Education");
        categories.put("Donation");
        return categories;
    }

    private static JSONArray defaultTypes() {
        JSONArray types = new JSONArray();
        try {
            JSONObject exp = new JSONObject();
            exp.put("name", "Expense");
            exp.put("val", "exp");
            exp.put("beh", -1);
            types.put(exp);

            JSONObject inc = new JSONObject();
            inc.put("name", "Income");
            inc.put("val", "inc");
            inc.put("beh", 1);
            types.put(inc);

            JSONObject tra = new JSONObject();
            tra.put("name", "Transfer");
            tra.put("val", "tra");
            tra.put("beh", 0);
            types.put(tra);
        } catch (Exception ignored) {
        }
        return types;
    }

    private static String chooseAccount(JSONArray accounts, String bankName, String raw) {
        String fallback = accounts.optString(0, "Cash");
        String normalizedBank = bankName == null ? "" : bankName.toLowerCase();

        if ("hana card".equals(normalizedBank)) {
            String account = findHanaCardAccount(accounts);
            return account != null ? account : ensureAccount(accounts, "Hana Credit");
        }

        if ("hana bank".equals(normalizedBank)) {
            String account = findHanaBankAccount(accounts);
            if (account != null) return account;
            return ensureAccount(accounts, "Hana Bank");
        }

        if ("jb bank".equals(normalizedBank)) {
            String account = findAccount(accounts, "jb");
            if (account != null) return account;
        }

        if (raw != null && (raw.contains("하나,") || raw.contains("잔액") || raw.contains("출금") || raw.contains("입금"))) {
            String account = findHanaBankAccount(accounts);
            if (account != null) return account;
        }

        return fallback;
    }

    private static String findHanaBankAccount(JSONArray accounts) {
        for (int i = 0; i < accounts.length(); i++) {
            String account = accounts.optString(i, "");
            String lower = account.toLowerCase(Locale.ROOT);
            if (lower.contains("hana") && isBankAccountName(lower) && !isCardAccountName(lower)) {
                return account;
            }
        }

        for (int i = 0; i < accounts.length(); i++) {
            String account = accounts.optString(i, "");
            String lower = account.toLowerCase(Locale.ROOT);
            if (lower.contains("hana") && !isCardAccountName(lower)) {
                return account;
            }
        }

        return null;
    }

    private static String findHanaCardAccount(JSONArray accounts) {
        for (int i = 0; i < accounts.length(); i++) {
            String account = accounts.optString(i, "");
            String lower = account.toLowerCase(Locale.ROOT);
            if (lower.contains("hana") && isCardAccountName(lower)) {
                return account;
            }
        }

        for (int i = 0; i < accounts.length(); i++) {
            String account = accounts.optString(i, "");
            String lower = account.toLowerCase(Locale.ROOT);
            if (isCardAccountName(lower)) {
                return account;
            }
        }

        return null;
    }

    private static boolean isBankAccountName(String lower) {
        return lower.contains("bank")
                || lower.contains("account")
                || lower.contains("ဘဏ်")
                || lower.contains("အကောင့်")
                || lower.contains("အကောင့်");
    }

    private static boolean isCardAccountName(String lower) {
        return lower.contains("credit")
                || lower.contains("card")
                || lower.contains("အကြွေး")
                || lower.contains("ဝယ်ကဒ်")
                || lower.contains("ကဒ်")
                || lower.contains("ကတ်");
    }

    private static String ensureAccount(JSONArray accounts, String accountName) {
        if (!containsString(accounts, accountName)) {
            accounts.put(accountName);
        }
        return accountName;
    }

    private static String findAccount(JSONArray accounts, String... keywords) {
        for (int i = 0; i < accounts.length(); i++) {
            String account = accounts.optString(i, "");
            String lower = account.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (lower.contains(keyword)) return account;
            }
        }
        return null;
    }

    private static boolean entryExists(JSONArray entries, String date, double amount, String type, String category, String account) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            boolean sameDate = date.equals(entry.optString("date", ""));
            boolean sameType = type.equals(entry.optString("typeVal", ""));
            boolean sameCategory = category.equals(entry.optString("category", ""));
            boolean sameAccount = account.equals(entry.optString("account", ""));
            boolean sameAmount = Math.abs(amount - entry.optDouble("amount", 0)) < 0.1;
            if (sameDate && sameType && sameCategory && sameAccount && sameAmount) return true;
        }
        return false;
    }

    private static boolean containsString(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) return true;
        }
        return false;
    }

    private static void showSmsImportNotification(Context context, int count) {
        createSmsImportChannel(context);

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Smart Wallet")
                .setContentText("Imported " + count + " new SMS transaction" + (count == 1 ? "" : "s"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(false)
                .setNumber(count)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    private static void createSmsImportChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SMS Imports",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
