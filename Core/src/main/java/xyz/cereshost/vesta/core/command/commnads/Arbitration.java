package xyz.cereshost.vesta.core.command.commnads;

import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.message.DiscordNotification;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.abitrage.TriangularArbitrage;
import xyz.cereshost.vesta.core.trading.real.api.BinanceWebSocketFull;
import xyz.cereshost.vesta.core.trading.real.api.BinanceWebSocketRequest;
import xyz.cereshost.vesta.core.utils.LoaderIndicator;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Arbitration extends BaseCommand {
    public Arbitration() {
        super("Ejecuta una estrategia de arbitraje triangular");
    }

    private final Set<String> lastOpportunities = new HashSet<>();

    @Override
    public void execute(Arguments arguments) throws Exception {

        BinanceWebSocketFull api = new BinanceWebSocketFull(false);

        LoaderIndicator loaderIndicator = new LoaderIndicator(5);
        loaderIndicator.setLabel("Buscado Arbitrajes...");

        AtomicLong windowStart = new AtomicLong(-1);

        MediaNotification mediaNotification = MediaNotification.empty();
        mediaNotification.updateStatusType(MediaNotification.StatusType.WORKING);
        mediaNotification.updateStatus("Analizado todos los mercados");
        TriangularArbitrage triangularArbitrage = new TriangularArbitrage(api, opportunities -> {
            updateLoader(loaderIndicator);
            updateStatus(mediaNotification);
            Set<String> current = new HashSet<>();
            for (TriangularArbitrage.TriangularArbitrageOpportunity opportunity : opportunities) current.add(buildKey(opportunity));
            boolean changed = !current.equals(lastOpportunities);
            if (!changed) {
                return;
            }

            if (current.isEmpty() && !lastOpportunities.isEmpty()) {
                loaderIndicator.clearLine();
                long duration = System.currentTimeMillis() - windowStart.get();
                mediaNotification.info("Ventana de arbitraje detectada: **%s** duró **%d ms**",
                        String.join(", ", lastOpportunities),
                        duration
                );
                Vesta.info("Fin de ventana de arbitraje (%d ms)", duration);
                windowStart.set(-1);
            }

            if (!current.isEmpty()) {
                loaderIndicator.clearLine();
                if (windowStart.get() == -1) {
                    windowStart.set(System.currentTimeMillis());
                    Vesta.info("Inicio ventana de arbitraje");
                }
                Vesta.info("Arbitrajes detectados: %d", opportunities.size());
                for (int i = 0; i < opportunities.size(); i++) {
                    TriangularArbitrage.TriangularArbitrageOpportunity opportunity = opportunities.get(i);
                    Vesta.info(
                            "[%d] %s | retorno %.6f | profit %.4f%% | peso %.8f",
                            i + 1,
                            String.join(" -> ", opportunity.assetsCycle()),
                            opportunity.rateProduct(),
                            opportunity.profitPercent(),
                            opportunity.totalWeight()
                    );
                    for (TriangularArbitrage.ArbitrageEdge edge : opportunity.edges()) {
                        Vesta.info(
                                "    %s %s via %s @ %.10f -> rate %.10f",
                                edge.action(),
                                edge.fromAsset().symbol + "/" + edge.toAsset().symbol,
                                edge.symbol(),
                                edge.referencePrice(),
                                edge.rate()
                        );
                    }
                }
            }
            lastOpportunities.clear();
            lastOpportunities.addAll(current);
        });
        triangularArbitrage.startSearch(Main.EXECUTOR);
    }

    private String buildKey(TriangularArbitrage.TriangularArbitrageOpportunity o) {
        return String.join("->", o.assetsCycle());
    }

    private final Queue<Long> deltas = new LinkedList<>();
    private final AtomicLong start = new AtomicLong(System.currentTimeMillis());

    private void updateLoader(LoaderIndicator loaderIndicator) {
        long time = System.currentTimeMillis();
        if (deltas.size() > 60) deltas.poll();
        deltas.offer(time - start.get());
        start.set(time);
        double avg = deltas.stream().mapToLong(Long::longValue).average().getAsDouble();
        loaderIndicator.setLabel("%dms (%.2fu/s) Buscando posibles arbitrajes...".formatted((int) avg, 1000/avg));
        loaderIndicator.printAndNexStep();
    }

    private long coolDown = System.currentTimeMillis();

    public void updateStatus(MediaNotification media) {
        long time = System.currentTimeMillis();
        if (coolDown < time) {
            media.updateStatus("Buscado Arbitrajes... %.2fu/s",1000 / deltas.stream().mapToLong(Long::longValue).average().getAsDouble());
            coolDown = time + TimeUnit.SECONDS.toMillis(15);
        }
    }
}
