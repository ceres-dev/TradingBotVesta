package xyz.cereshost.vesta.core.command.commnads;

import xyz.cereshost.vesta.common.Vesta;
import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.message.DiscordNotification;
import xyz.cereshost.vesta.core.message.MediaNotification;
import xyz.cereshost.vesta.core.trading.abitrage.TriangularArbitrage;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApi;
import xyz.cereshost.vesta.core.trading.real.api.BinanceApiRest;
import xyz.cereshost.vesta.core.utils.LoaderIndicator;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class Arbitration extends BaseCommand {
    public Arbitration() {
        super("Ejecuta una estrategia de arbitraje triangular");
    }

    private final Set<String> lastOpportunities = new HashSet<>();

    @Override
    public void execute(Arguments arguments) throws Exception {

        BinanceApi api = new BinanceApiRest(false, true);

        LoaderIndicator loaderIndicator = new LoaderIndicator(1);
        loaderIndicator.setLabel("Buscado Arbitrajes...");

        AtomicLong windowStart = new AtomicLong(-1);

        MediaNotification mediaNotification = new DiscordNotification();
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
                                edge.fromAsset() + "/" + edge.toAsset(),
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
        if (deltas.size() > 15) deltas.poll();
        deltas.offer(time - start.get());
        start.set(time);
        double avg = deltas.stream().mapToLong(Long::longValue).average().getAsDouble();
        loaderIndicator.setLabel("%dms (%.2fu/s) Buscando posibles arbitrajes...".formatted((int) avg, 1000/avg));
        loaderIndicator.printAndNexStep();
    }

    private int counter = 0;

    public void updateStatus(MediaNotification media) {
        if (counter < 25) {
            counter++;
            return;
        }
        media.updateStatus("Buscado Arbitrajes... %.2fu/s",1000 / deltas.stream().mapToLong(Long::longValue).average().getAsDouble());
        counter = 0;
    }
}
