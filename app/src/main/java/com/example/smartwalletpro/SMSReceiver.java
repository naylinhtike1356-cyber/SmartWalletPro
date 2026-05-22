package com.example.smartwalletpro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SMSReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "SMS_BUFFER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null && intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    StringBuilder fullMessage = new StringBuilder();
                    String format = bundle.getString("format", SmsMessage.FORMAT_3GPP);
                    for (Object pdu : pdus) {
                        if (pdu == null || !(pdu instanceof byte[])) continue;
                        SmsMessage smsMessage = null;
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                            } else {
                                smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                            }
                        } catch (Exception e) { /* ignore corrupt PDU */ }
                        if (smsMessage != null) {
                            String body = smsMessage.getMessageBody();
                            if (body != null) fullMessage.append(body);
                        }
                    }
                    if (fullMessage.length() > 0) parseAndSaveSMS(context, fullMessage.toString());
                }
            }
        }
    }

    private void parseAndSaveSMS(Context context, String text) {
        String cleanText = text.replace(",", ""); // Remove commas for easier amount parsing
        String fullTextLower = cleanText.toLowerCase();

        double amount = 0;
        String type = "";
        String note = "";
        String dateStr = "";
        String bankName = "General";

        // 1. Robust Bank/Account & Type Detection
        if (text.contains("하나")) {
            // Card Check: Look for the characteristic "하나[digits]승인" or "승인" or "누적"
            boolean isCardFormat = text.matches("(?s).*하나\\d*승인.*") || text.contains("승인") || text.contains("누적") || text.contains("일시불");
            
            // Bank Check: Look for Hana with comma (하나,), Balance (잔액), Withdrawal (출금), or Deposit (입금)
            boolean isBankFormat = text.contains("하나,") || text.contains("잔액") || text.contains("출금") || text.contains("입금");

            if (isCardFormat) {
                // If it has card keywords, it's a Card, unless it also has specific Bank keywords like '잔액'
                if (text.contains("잔액") || text.contains("하나,")) {
                    bankName = "Hana Bank";
                } else {
                    bankName = "Hana Card";
                }
            } else if (isBankFormat) {
                bankName = "Hana Bank";
            }
        } else if (text.contains("전북") || fullTextLower.contains("jb")) {
            bankName = "JB Bank";
        }



        if (text.contains("승인") || text.contains("출금") || text.contains("지급") || text.contains("결제") || fullTextLower.contains("withdrawal")) {
            type = "exp";
        } else if (text.contains("입금") || text.contains("취소") || fullTextLower.contains("deposit")) {
            type = "inc";
        }


        // 2. Extract Amount
        Matcher amountMatcher = Pattern.compile("(\\d+)\\s*(원|KRW)").matcher(cleanText);
        if (amountMatcher.find()) {
            amount = Double.parseDouble(amountMatcher.group(1));
        }

        // 3. Extract Date
        Matcher dateMatcher = Pattern.compile("(\\d{2}/\\d{2})").matcher(cleanText);
        if (dateMatcher.find()) {
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);
            dateStr = currentYear + "-" + dateMatcher.group(1).replace("/", "-");
        } else {
            dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
        }

        // 4. Extract Note/Vendor (Improved Heuristics)
        if ("Hana Card".equals(bankName)) {
            // Pattern 1: [Time] [Vendor] 누적
            Matcher cardNoteMatcher = Pattern.compile("\\d{2}:\\d{2}\\s+(.*?)\\s+누적").matcher(cleanText);
            if (cardNoteMatcher.find()) {
                note = cardNoteMatcher.group(1).trim();
            } else {
                // Pattern 2: [Approval] [Name] [Amount] [PaymentType] [Date] [Time] [Vendor] 누적
                // Vendor is usually between the time and '누적'
                String[] parts = cleanText.split("\\s+");
                int accumIdx = -1;
                for(int i=0; i<parts.length; i++) if(parts[i].contains("누적")) { accumIdx = i; break; }
                if(accumIdx > 0) {
                    note = parts[accumIdx - 1]; // Take the part right before '누적'
                }
            }
        } else if ("Hana Bank".equals(bankName)) {
            // Multi-line: Vendor is on the line before '잔액'
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.contains("잔액") && i > 0) {
                    note = lines[i-1].trim();
                    // If note is just the account number, go up one more
                    if (note.matches(".*\\d{3}.*") && i > 1 && lines[i-2].length() > 2) {
                        note = lines[i-2].trim();
                    }
                    break;
                }
            }
        }

        // Fallback Note: packed format [Time] [Vendor] [Keyword:누적/잔액]
        if (note.isEmpty()) {
            Matcher packedMatcher = Pattern.compile("\\d{2}:\\d{2}\\s+(.*?)(?=\\s+(?:누적|잔액)|$)").matcher(cleanText);
            if (packedMatcher.find()) {
                note = packedMatcher.group(1).trim();
            }
        }


        // General multi-line fallback
        if (note.isEmpty() || note.length() < 2) {
            String[] lines = cleanText.split("\n");
            for (String l : lines) {
                l = l.trim();
                String lowerL = l.toLowerCase();
                boolean isHeader = l.contains("Web") || lowerL.contains("hana") || lowerL.contains("bank");
                boolean isAmountLine = l.contains("원") || l.contains("KRW");
                boolean isDateLine = l.contains("/") || l.contains(":");
                boolean isAccountLine = (l.contains("*") && l.length() > 5) || l.contains("704");
                boolean isBalanceLine = l.contains("잔액") || l.contains("누적") || lowerL.contains("balance");

                if (!isHeader && !isAmountLine && !isDateLine && !isAccountLine && !isBalanceLine && l.length() > 1) {
                    note = l;
                    break;
                }
            }
        }

        // Final Fallback
        if (note.isEmpty() || note.length() < 2) {
            note = (type.equals("inc") ? "Deposit" : "Payment");
        }

        if (note.length() > 50) note = note.substring(0, 50).trim();


        // 5. Categorize
        String category = getCategoryFromNote(note, cleanText);

        // 6. Save if valid
        if (amount > 0 && !type.isEmpty()) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long currentTime = System.currentTimeMillis();

            long lastTime = prefs.getLong("last_sms_time", 0);
            double lastAmount = Double.longBitsToDouble(prefs.getLong("last_sms_amount", Double.doubleToLongBits(0)));
            String lastType = prefs.getString("last_sms_type", "");

            // Duplicate check: within 2 minutes, same amount and type
            boolean isDuplicate = (currentTime - lastTime < 120000) && (Math.abs(amount - lastAmount) < 0.1) && type.equals(lastType);

            if (!isDuplicate) {
                try {
                    JSONObject newItem = new JSONObject();
                    newItem.put("amount", amount);
                    newItem.put("type", type);
                    newItem.put("note", note);
                    newItem.put("category", category);
                    newItem.put("bankName", bankName);
                    newItem.put("raw", text);
                    newItem.put("dateStr", dateStr);
                    newItem.put("ts", currentTime);

                    if (WalletStorage.importSmsTransaction(context, newItem)) {
                        prefs.edit()
                                .putLong("last_sms_time", currentTime)
                                .putLong("last_sms_amount", Double.doubleToLongBits(amount))
                                .putString("last_sms_type", type)
                                .apply();
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    /**
     * Smart Auto-Categorization: match keywords in note/raw SMS to app categories.
     * Extensible for future keywords (e.g. Korail, CU, GS25, Starbucks).
     */
    private String getCategoryFromNote(String note, String rawText) {
        if (note == null) note = "";
        String combined = (note + " " + (rawText != null ? rawText : "")).toLowerCase();

        // Transport
        if (combined.contains("korail") || combined.contains("subway") || combined.contains("버스") || combined.contains("지하철")
                || combined.contains("taxi") || combined.contains("카카오톡") || combined.contains("t-money") || combined.contains("교통")) {
            return "🚌 Transport";
        }
        // Food & Convenience
        if (combined.contains("cu ") || combined.contains(" cu ") || combined.endsWith(" cu") || combined.contains("씨유") || combined.contains("gs25")
                || combined.contains("emart") || combined.contains("이마트") || combined.contains("편의점")) {
            return "🍔 Food";
        }
        if (combined.contains("starbucks") || combined.contains("스타벅스") || combined.contains("cafe") || combined.contains("카페")
                || combined.contains("coffee") || combined.contains("맥도날드") || combined.contains("mcdonald")
                || combined.contains("배달") || combined.contains("요기요") || combined.contains("배민")) {
            return "🍔 Food";
        }
        // Shopping
        if (combined.contains("coupang") || combined.contains("쿠팡") || combined.contains("gmarket") || combined.contains("지마켓")
                || combined.contains("naver") || combined.contains("네이버") || combined.contains("쇼핑")) {
            return "🛍️ Shopping";
        }
        // Bills & Utilities
        if (combined.contains("통신") || combined.contains("sk") || combined.contains("kt") || combined.contains("lg")
                || combined.contains("전기") || combined.contains("가스") || combined.contains("공과금")) {
            return "💡 Bills";
        }
        // Health
        if (combined.contains("약국") || combined.contains("병원") || combined.contains("hospital") || combined.contains("pharmacy")) {
            return "💊 Health";
        }
        // Education
        if (combined.contains("학원") || combined.contains("교육") || combined.contains("교재")) {
            return "🎓 Education";
        }
        // Rent
        if (combined.contains("월세") || combined.contains("전세") || combined.contains("rent") || combined.contains("집")) {
            return "🏠 Rent";
        }
        return "General";
    }
}
