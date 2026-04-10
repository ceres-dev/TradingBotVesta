package xyz.cereshost.vesta.core.command.commnads;

import xyz.cereshost.vesta.core.Main;
import xyz.cereshost.vesta.core.command.Arguments;
import xyz.cereshost.vesta.core.command.BaseCommand;
import xyz.cereshost.vesta.core.packet.PacketHandlerServer;

public class Server extends BaseCommand {
    public Server() {
        super("Gestiona el server");
    }

    @Override
    public void execute(Arguments arguments) throws Exception {
        PacketHandlerServer server = Main.getServer();
        switch (arguments.get(0).toLowerCase()) {
            case "start" -> {
                if (!server.isStared()){
                    server.start();
                }
            }
            case "stop" -> {
                if (server.isStared()){
                    server.stop();
                }
            }
            case "restart" -> {
                if (server.isStared()){
                    server.stop();
                    server.start();
                }else {
                    server.start();
                }
            }
        }
    }
}
