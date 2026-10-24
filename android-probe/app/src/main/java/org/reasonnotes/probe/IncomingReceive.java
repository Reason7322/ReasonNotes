package org.reasonnotes.probe;

import android.content.Context;
import java.io.File;
import java.util.function.IntConsumer;

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
        return scanPhone(context, repository, imported -> {});
    }

    public static ExchangeDirectory.ScanReport scanPhone(
            Context context, ProbeRepository repository, IntConsumer progress) {
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
                context, repository, ledger, receipts, "android", progress));
    }

    public static ExchangeDirectory.ScanReport scanPhone(
            Context context, CurrentNoteStore store, IntConsumer progress) {
        return run(() -> ExchangeDirectory.scan(context, store, "android", progress));
    }

    public static String status(ExchangeDirectory.ScanReport report) {
        String success = null;
        if (report.imported() > 0 && report.confirmed() > 0) {
            success = "Received " + report.imported() + " revisions · confirmed "
                    + report.confirmed();
        } else if (report.imported() > 0) {
            success = "Received " + report.imported() + " note revisions";
        } else if (report.confirmed() > 0) {
            success = "Confirmed " + report.confirmed() + " deliveries";
        }
        // A warning about an old receipt must not conceal a successful new
        // note import. Keep the warning visible rather than swallowing it.
        if (!report.errors().isEmpty()) {
            String warning = "Incoming check: " + report.errors().get(0);
            return success == null ? warning : success + " · " + warning;
        }
        return success;
    }
}
