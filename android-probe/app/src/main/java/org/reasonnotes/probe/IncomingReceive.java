package org.reasonnotes.probe;

import android.content.Context;
import java.io.File;

/**
 * Receive-only Android entry point. It deliberately has no KDE Connect sender,
 * outbound-selection or retry API: opening notes must never transmit files.
 */
public final class IncomingReceive {
    @FunctionalInterface public interface Scanner {
        ExchangeDirectory.ScanReport scan();
    }

    private IncomingReceive() {}

    public static ExchangeDirectory.ScanReport run(Scanner scanner) {
        return scanner.scan();
    }

    public static ExchangeDirectory.ScanReport scanPhone(
            Context context, ProbeRepository repository) {
        File files = context.getFilesDir();
        File root = new File(files, "probe-repository");
        DeliveryLedger ledger = new DeliveryLedger(
                SyncActivity.deliveryStateDirectory(files), 3);
        ReceiptStore receipts = new ReceiptStore(root, 3);
        AppPreferences preferences = new AppPreferences(new AndroidLocalPreferences(
                context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)));
        // Preserve legacy physical-ID confirmations without resetting attempts.
        SyncActivity.preparePeerLedger(ledger, preferences.targetDeviceId());
        return run(() -> ExchangeDirectory.scan(
                context, repository, ledger, receipts, "android"));
    }

    public static String status(ExchangeDirectory.ScanReport report) {
        if (!report.errors().isEmpty()) {
            return "Incoming check: " + report.errors().get(0);
        }
        if (report.imported() == 0 && report.confirmed() == 0) return null;
        if (report.imported() > 0 && report.confirmed() > 0) {
            return "Received " + report.imported() + " revisions · confirmed "
                    + report.confirmed();
        }
        return report.imported() > 0
                ? "Received " + report.imported() + " note revisions"
                : "Confirmed " + report.confirmed() + " deliveries";
    }
}
