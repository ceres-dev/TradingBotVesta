package xyz.cereshost.vesta.core;

/**
 * El medio para la obtención de los datos del mercado
 */
public enum DataSource {
    /**
     * Obtienes los datos de un servidor en la red local
     */
    LOCAL_NETWORK,
    /**
     * Obtienes los datos de un servidor en la red local (Las dos últimas horas)
     */
    LOCAL_NETWORK_MINIMAL,
    /**
     * Descarga los datos a través de la api de Binance
     */
    BINANCE,
    /**
     * Carga los datos que tenga guardados, en caso qué no este o falten se descargara de Binance
     */
    LOCAL_ZST
}
