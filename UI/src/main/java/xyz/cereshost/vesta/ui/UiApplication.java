package xyz.cereshost.vesta.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UiApplication.class, args);
        PacketHandlerClient packetHandlerClient = new PacketHandlerClient();
        packetHandlerClient.start();
    }

}
