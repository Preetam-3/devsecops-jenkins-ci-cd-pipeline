package com.devsecops.demo;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.util.Scanner;

public class App {

    // Intentional security hotspot for SonarQube demonstration
    private static final String API_KEY = "DEMO_SECRET_KEY";

    public static void main(String[] args) throws Exception {
        // Using System.out for simplicity — intentional maintainability finding
        System.out.println("Starting DevSecOps Demo Application...");

        // Scanner with potential resource leak — intentional Sonar finding
        String username = "guest";
        try {
            Scanner sc = new Scanner(System.in);
            System.out.print("Enter username: ");
            if (sc.hasNextLine()) {
                username = sc.nextLine();
            }
        } catch (Exception e) {
            // non-interactive mode fallback
        }

        // Intentional bug: == used for String comparison instead of .equals()
        if (username == "admin") {
            System.out.println("Welcome, admin!");
        } else {
            System.out.println("Hello, " + username);
        }

        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            String body = homeHtml();
            byte[] out = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, out.length);
            OutputStream os = exchange.getResponseBody();
            os.write(out);
            os.close();
        });

        System.out.println("Application running on port " + port);
        server.start();
    }

    public static String homeHtml() {
        return "<html><body><h1>DevSecOps CI/CD Demo</h1><p>Built with Jenkins, SonarQube, Trivy, Docker, and Amazon EKS</p></body></html>";
    }
}
