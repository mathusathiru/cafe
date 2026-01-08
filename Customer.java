import helpers.*;
import java.net.Socket;
import java.io.*;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;


public class Customer {
    private static final AtomicBoolean isRunning = new AtomicBoolean(true);
    private static String customerName;

    private static Socket socket;
    private static PrintWriter out;

    private static Thread messageThread;
    private static ServerMessageHandler messageHandler;

    // handling asynchronous messages from the server
    private static class ServerMessageHandler implements Runnable {
        private final BufferedReader in;

        private volatile boolean running = true;

        public ServerMessageHandler(BufferedReader in) {
            this.in = in;
        }

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            try {
                while (running) {
                    String serverMessage = in.readLine();

                    if (serverMessage == null) {
                        System.err.println("error: lost connection to café server");
                        close();
                        break;
                    }

                    if (serverMessage.equals("exit")) {
                        break;
                    }

                    // build and print multi-line messages, including order status responses
                    StringBuilder fullMessage = new StringBuilder(serverMessage);
                    while (in.ready()) {
                        String additionalLine = in.readLine();
                        if (additionalLine == null) break;
                        fullMessage.append("\n").append(additionalLine);
                    }
                    System.out.println(fullMessage);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("error reading from server: " + e.getMessage());
                    close();
                }
            }
        }
    }

    // control interrupts of Ctrl+C to ensure smooth disconnection and item repurposing
    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nreceived interrupt signal (Ctrl-C) - exiting café...");
            close();
        }));
    }

    // notifies server of exit and closes sock connection
    private static void close() {

        // prevent new inputs being processed
        isRunning.set(false);

        // inform server of disconnection if still connected
        if (out != null && socket != null && !socket.isClosed()) {
            out.println("exit");
        }

        // stop processing server messages
        if (messageHandler != null) {
            messageHandler.stop();
        }

        // close thread and provide time to prevent abrupt exit, or immediately interrupt if the timeout has passed
        if (messageThread != null) {
            try {
                messageThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            if (messageThread.isAlive()) {
                messageThread.interrupt();
            }
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        System.out.println("thank you for visiting our café " + customerName + "! ☕");
    }

    // display welcome message and prompt user for name input
    private static void connectCustomer(BufferedReader in, Scanner userInput) throws IOException {
        for (int i = 0; i < 3; i++) {
            System.out.println(in.readLine());
        }

        // :: Send customer name and get confirmation
        customerName = userInput.nextLine();
        out.println(customerName);
        System.out.println(in.readLine());
    }

    public static void main(String[] args) {

        setupShutdownHook();

        try {
            // create new socket for customer
            socket = new Socket("localhost", 8888);
            Scanner userInput = new Scanner(System.in);
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            connectCustomer(in, userInput);

            messageHandler = new ServerMessageHandler(in);
            messageThread = new Thread(messageHandler);
            messageThread.start();

            // keep processing until the user exists or a client side error occurs
            String input;
            while (isRunning.get()) {
                input = userInput.nextLine();
                out.println(input);
                if (!isRunning.get() || input.equals("exit")) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
        } finally {
            close();
        }
    }
}