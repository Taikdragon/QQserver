package src;

import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int PORT = 12345;
    private static Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) {
        System.out.println("QQ聊天服务器已启动...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("新客户端连接: " + clientSocket);

                ClientHandler clientHandler = new ClientHandler(clientSocket);
                clients.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("服务器异常: " + e.getMessage());
        }
    }

    // 广播消息给所有客户端
    public static void broadcast(String message, ClientHandler excludeClient) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != excludeClient) {
                    client.sendMessage(message); // 必须调用此方法
                }
            }
        }
    }

    // 客户端处理线程
    private static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private String username;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                this.out = out;

                // 获取用户名
                username = in.readLine();
                System.out.println(username + " 加入聊天室");

                // 通知其他用户
                broadcast(username + " 加入了聊天", this);

                // 持续监听消息
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    if (inputLine.startsWith("/quit")) {
                        break;
                    }
                    broadcast("[" + username + "]: " + inputLine, this);
                }

            } catch (IOException e) {
                System.out.println(username + " 的连接异常断开");
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
                clients.remove(this);
                broadcast(username + " 离开了聊天", this);
                System.out.println(username + " 已断开连接");
            }
        }

        public void sendMessage(String message) {
            out.println(message);
        }
    }
}