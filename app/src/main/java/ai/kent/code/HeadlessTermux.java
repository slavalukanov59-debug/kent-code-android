package ai.kent.code;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Checks an authenticated loopback and, with consent, starts its fixed helper via Termux. */
final class HeadlessTermux {
    private static final long START_TIMEOUT = TimeUnit.SECONDS.toNanos(10);
    private static final long START_COOLDOWN = TimeUnit.SECONDS.toNanos(25);

    record Capabilities(boolean enabled, boolean installed, boolean permission, boolean runCommandAvailable,
                        boolean tokenConfigured) {}

    interface Platform {
        Capabilities capabilities();
        LocalTermuxBridge.Health probe(BooleanSupplier cancelled);
        TermuxBridge.BackgroundStart start(BooleanSupplier cancelled) throws IOException;
        boolean autoStartEnabled();
        void setAutoStartEnabled(boolean enabled);
    }

    static final class Status {
        final boolean enabled, installed, permission, runCommandAvailable, tokenConfigured;
        final LocalTermuxBridge.Health.Status localStatus;
        final String detail;
        private final Capabilities capabilities;

        Status(Capabilities capabilities, LocalTermuxBridge.Health.Status localStatus, String detail) {
            this.capabilities = capabilities;
            enabled = capabilities.enabled();
            installed = capabilities.installed();
            permission = capabilities.permission();
            runCommandAvailable = capabilities.runCommandAvailable();
            tokenConfigured = capabilities.tokenConfigured();
            this.localStatus = localStatus;
            this.detail = detail;
        }

        boolean localReady() {
            return localStatus == LocalTermuxBridge.Health.Status.READY
                    || localStatus == LocalTermuxBridge.Health.Status.LEGACY;
        }

        boolean officialAvailable() { return enabled && installed && permission && runCommandAvailable; }

        Status withDetail(String message) { return new Status(capabilities, localStatus, message); }

        Status cancelled(String message) {
            return new Status(capabilities, LocalTermuxBridge.Health.Status.CANCELLED, message);
        }
    }

    private final Platform platform;
    private final ReentrantLock startup = new ReentrantLock();
    private volatile boolean appVisible;
    private long lastAcceptedStart;

    HeadlessTermux(SecureKeyStore settings, TermuxBridge official, LocalTermuxBridge loopback) {
        this(new Platform() {
            @Override public Capabilities capabilities() {
                return new Capabilities(settings.bridgeEnabled(), official.installed(), official.hasPermission(),
                        official.runCommandAvailable(), loopback.configured());
            }
            @Override public LocalTermuxBridge.Health probe(BooleanSupplier cancelled) { return loopback.probe(cancelled); }
            @Override public TermuxBridge.BackgroundStart start(BooleanSupplier cancelled) throws IOException {
                return official.startLocalBridge(cancelled);
            }
            @Override public boolean autoStartEnabled() { return settings.bridgeAutoStart(); }
            @Override public void setAutoStartEnabled(boolean enabled) { settings.bridgeAutoStart(enabled); }
        });
    }

    HeadlessTermux(Platform platform) { this.platform = platform; }

    void setAppVisible(boolean visible) { appVisible = visible; }
    boolean autoStartEnabled() { return platform.autoStartEnabled(); }
    void setAutoStartEnabled(boolean enabled) { platform.setAutoStartEnabled(enabled); }

    Status check(BooleanSupplier cancelled) {
        Capabilities caps = platform.capabilities();
        if (CommandResult.cancelled(cancelled))
            return new Status(caps, LocalTermuxBridge.Health.Status.CANCELLED, "Проверка подключения остановлена.");
        if (!caps.enabled())
            return new Status(caps, LocalTermuxBridge.Health.Status.UNCONFIGURED, "Включите интеграцию Termux.");
        if (!caps.tokenConfigured()) {
            String guidance;
            if (!caps.installed()) guidance = "Termux не найден. Настройте его и сохраните токен помощника.";
            else if (caps.runCommandAvailable() && caps.permission())
                guidance = "Android разрешил RUN_COMMAND. Для передачи проекта сохраните токен локального помощника.";
            else guidance = "Сохраните токен локального помощника Termux.";
            return new Status(caps, LocalTermuxBridge.Health.Status.UNCONFIGURED, guidance);
        }
        LocalTermuxBridge.Health health = platform.probe(cancelled);
        String detail = health.detail;
        if (health.status == LocalTermuxBridge.Health.Status.OFFLINE) {
            if (!caps.installed()) detail += " Termux не найден.";
            else if (!caps.runCommandAvailable()) detail += " Запустите помощник один раз в Termux вручную.";
            else if (!caps.permission()) detail += " Для запуска из Kent Code выдайте разрешение RUN_COMMAND.";
        }
        return new Status(caps, health.status, detail);
    }

    /** allowStart comes from explicit UI consent or the operator's saved opt-in. */
    Status connect(boolean allowStart, BooleanSupplier cancelled) {
        Status current = check(cancelled);
        if (!allowStart || !canLaunch(current)) return current;
        if (!appVisible) return current.withDetail("Откройте Kent Code, чтобы запустить помощник Termux.");
        BooleanSupplier stop = () -> !appVisible || CommandResult.cancelled(cancelled);
        try {
            while (!startup.tryLock(200, TimeUnit.MILLISECONDS)) {
                if (stop.getAsBoolean()) return current.cancelled("Ожидание подключения остановлено.");
            }
            try {
                current = check(stop);
                if (!canLaunch(current)) return current;
                if (lastAcceptedStart != 0 && System.nanoTime() - lastAcceptedStart < START_COOLDOWN) {
                    return current.withDetail("Недавний запуск уже передан Termux. Дождитесь помощника или проверьте его в Termux.");
                }
                return launch(current, stop);
            } finally {
                startup.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return current.cancelled("Ожидание подключения остановлено.");
        }
    }

    private static boolean canLaunch(Status status) {
        return status.officialAvailable() && status.tokenConfigured
                && status.localStatus == LocalTermuxBridge.Health.Status.OFFLINE;
    }

    private Status launch(Status current, BooleanSupplier stop) throws InterruptedException {
        try (TermuxBridge.BackgroundStart request = platform.start(stop)) {
            lastAcceptedStart = System.nanoTime();
            long deadline = lastAcceptedStart + START_TIMEOUT;
            do {
                if (stop.getAsBoolean()) return current.cancelled("Ожидание подключения остановлено.");
                Status observed = check(stop);
                if (observed.localReady()) {
                    lastAcceptedStart = 0;
                    return observed;
                }
                if (observed.localStatus != LocalTermuxBridge.Health.Status.OFFLINE
                        && observed.localStatus != LocalTermuxBridge.Health.Status.TIMEOUT) return observed;
                CommandResult ended = request.completed();
                if (ended != null) return observed.withDetail(launchFailure(ended));
                Thread.sleep(200);
                current = observed;
            } while (System.nanoTime() < deadline);
            return current.withDetail("Termux принял запрос запуска. Помощник ещё не подтвердил подключение. "
                    + "Проверьте настройку или запустите его в Termux вручную.");
        } catch (IOException failure) {
            if (stop.getAsBoolean()) return current.cancelled("Запуск помощника остановлен.");
            String detail = failure.getMessage();
            return current.withDetail(detail == null || detail.isEmpty() ? "Не удалось запустить помощник Termux."
                    : SensitiveData.redact(detail));
        }
    }

    private static String launchFailure(CommandResult result) {
        String code = result.exitCode == null ? "" : " Код выхода " + result.exitCode + ".";
        return "Помощник Termux завершился до подтверждения подключения." + code
                + " Проверьте Python, файл ~/.kent-bridge/server.py и allow-external-apps=true или запустите мост вручную.";
    }
}
