package com.example.smartwalletpro;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Smart Notifications: Budget alerts via WorkManager.
 * When monthly spent >= 80% of limit, show a notification.
 */
public class BudgetAlertWorker extends Worker {

    private static final String PREFS_BUDGET = "BUDGET_ALERT";
    private static final String CHANNEL_ID = "smart_wallet_budget";

    public BudgetAlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS_BUDGET, Context.MODE_PRIVATE);
        String jsonData = prefs.getString("notification_data", null);
        
        if (jsonData == null) return Result.success();

        try {
            org.json.JSONObject data = new org.json.JSONObject(jsonData);
            String budgetEnd = data.optString("budgetEnd", "");
            
            java.util.Calendar cal = java.util.Calendar.getInstance();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
            String today = sdf.format(cal.getTime());
            
            boolean isLastDayOfMonth = cal.get(java.util.Calendar.DATE) == cal.getActualMaximum(java.util.Calendar.DATE);
            boolean isBudgetEnd = !budgetEnd.isEmpty() && today.equals(budgetEnd);

            if (isLastDayOfMonth || isBudgetEnd) {
                String title = isBudgetEnd ? "Smart Wallet Pro – ဘတ်ဂျက်ကုန်ဆုံးမှု ရှင်းတမ်း" : "Smart Wallet Pro – လချုပ် ရှင်းတမ်း";
                
                double mInc = data.optDouble("monthlyIncome", 0);
                double mExp = data.optDouble("monthlyExpense", 0);
                double tInc = data.optDouble("totalIncome", 0);
                double tExp = data.optDouble("totalExpense", 0);
                String curr = data.optString("currency", "");

                String body = String.format(java.util.Locale.getDefault(), 
                    "ဒီလချုပ်ရှင်းတမ်း - ဝင်ငွေ %.0f %s၊ ထွက်ငွေ %.0f %s\nစုစုပေါင်း - ဝင်ငွေ %.0f %s၊ ထွက်ငွေ %.0f %s ဖြစ်ပါတယ်။",
                    mInc, curr, mExp, curr, tInc, curr, tExp, curr);

                createChannel(ctx);
                NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setContentText(title)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true);

                NotificationManagerCompat.from(ctx).notify(2001, builder.build());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return Result.success();
    }

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Summaries", NotificationManager.IMPORTANCE_DEFAULT);
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
