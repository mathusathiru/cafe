import helpers.*;
import helpers.StateLogger;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class Barista {

    // atomic integers for thread synchronisation purposes
    private static final AtomicInteger totalCustomers = new AtomicInteger(0);
    private static final AtomicInteger waitingCustomers = new AtomicInteger(0);

    private static final AtomicInteger waitingTeas = new AtomicInteger(0);
    private static final AtomicInteger waitingCoffees = new AtomicInteger(0);
    private static final AtomicInteger brewingTeas = new AtomicInteger(0);
    private static final AtomicInteger brewingCoffees = new AtomicInteger(0);
    private static final AtomicInteger trayTeas = new AtomicInteger(0);
    private static final AtomicInteger trayCoffees = new AtomicInteger(0);

    // semaphores to ensure there is a maximum of two teas and two coffees brewing at a time
    private static final Semaphore teaSlots = new Semaphore(2);
    private static final Semaphore coffeeSlots = new Semaphore(2);

    // separate locks for different areas when processing items and disconnecting the client
    private static final ReentrantLock waitingLock = new ReentrantLock();
    private static final ReentrantLock brewingLock = new ReentrantLock();
    private static final ReentrantLock trayLock = new ReentrantLock();
    private static final ReentrantLock disconnectionLock = new ReentrantLock();

    private static final List<Order.OrderItem> waitingArea = new ArrayList<>();
    private static final List<Order.OrderItem> brewingArea = new ArrayList<>();
    private static final Map<CustomerInfo, List<Order.OrderItem>> trayArea = new HashMap<>();

    private static class CustomerSession {
        private final Socket clientSocket;
        private CustomerInfo customerInfo;
        private PrintWriter out;
        private Order currentOrder;
        private final Object orderLock = new Object();

        public CustomerSession(Socket socket) {
            this.clientSocket = socket;
        }

        private void initialiseConnection(BufferedReader in) throws IOException {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            out.println("welcome to the virtual café ☕\n");
            out.println("please enter your name to begin:");
            totalCustomers.incrementAndGet();
            Barista.logState();
        }

        private void handleCustomerRegistration(BufferedReader in) throws IOException {
            String name = in.readLine();

            if (name == null || name.trim().isEmpty()) {
                out.println("✗ name cannot be empty");
                return;
            }

            customerInfo = new CustomerInfo(name);
            registerOrderListener();

            out.println("\nhello " + name + "! you can\n" +
                    "- place an order (e.g., 'order 2 teas and 1 coffee')\n" +
                    "- check status ('order status')\n" +
                    "- collect your order ('collect')\n" +
                    "- leave the café ('exit')");
        }

        private void processCustomerInput(BufferedReader in) throws IOException {
            String input;
            while ((input = in.readLine()) != null) {
                if (handleCommand(input)) break;
            }
        }

        private boolean handleCommand(String command) {

            // match input strings through regex matching
            String action = RegexProcessor.identifyCommand(command);

            switch (action) {
                case "exit" -> {
                    out.println("exit");
                    return true;
                }
                case "collect" -> handleCollection();
                case "status" -> handleOrderStatus();
                case "order" -> {
                    int[] quantities = RegexProcessor.parseOrder(command);
                    if (quantities[0] > 0 || quantities[1] > 0) {
                        handleIncomingOrder(command);
                    } else {
                        out.println("✗ invalid order format");
                    }
                }
                default -> out.println("✗ invalid command");
            }
            return false;
        }

        private void handleIncomingOrder(String command) {

            // contain input command processing in regex processor
            final int[] quantities = RegexProcessor.parseOrder(command);

            synchronized (orderLock) {
                if (currentOrder == null) {
                    // for a new order from a customer
                    currentOrder = new Order(customerInfo, quantities[0], quantities[1]);
                    Barista.setClientWaiting(1);
                    out.println("✓ order received for " + currentOrder);
                    Barista.addToWaitingArea(currentOrder, quantities[0], quantities[1], true);
                } else if (currentOrder.isReadyForCollection()) {
                    // illogical to keep ordering if the order is already available for collection
                    out.println("✗ please collect your completed order before placing a new one");
                } else {
                    // to update an existing order from a customer
                    try {
                        currentOrder.addItems(quantities[0], quantities[1]);
                        out.println("✓ updated order for " + customerInfo.getCustomerName() + ": " + currentOrder);
                        Barista.addToWaitingArea(currentOrder, quantities[0], quantities[1], false);
                    } catch (IllegalStateException e) {
                        out.println("✗ error: " + e.getMessage());
                    }
                }
            }
        }

        private void handleOrderStatus() {
            if (currentOrder == null) {
                out.println("✗ no order found for " + customerInfo.getCustomerName());
            } else {
                String status = Barista.getOrderStatus(currentOrder);
                out.println(status);
                out.flush();
            }
        }

        private void handleCollection() {
            if (currentOrder == null) {
                out.println("✗ no order for " + customerInfo.getCustomerName() + " to collect");
                return;
            }

            if (!currentOrder.isReadyForCollection() || !Barista.collectOrder(currentOrder)) {
                out.println("✗ order not ready for " + customerInfo.getCustomerName() + " yet");
                return;
            }

            currentOrder = null;
            Barista.setClientWaiting(-1);
            out.println("✓ order collected for " + customerInfo.getCustomerName());
        }

        private void disconnectClient() {
            if (customerInfo == null) {
                return;
            }

            try {
                Barista.handleClientDisconnection(customerInfo, currentOrder, totalCustomers);
                if (!clientSocket.isClosed()) {
                    // in case of any errors in closing client in the server class
                    out.flush();
                    clientSocket.close();
                }
            } catch (IOException e) {
                out.println("✗ error: " + e.getMessage());
            }
        }

        // listens for a completed order or notification of repurposed items, separate to customer input thread
        private void registerOrderListener() {
            OrderListeners.registerListener(customerInfo, new OrderListeners.OrderListener() {
                @Override
                public void onOrderCompleted(Order order) {
                    if (out != null) {
                        out.println("order for " + order.getCustomer().getCustomerName() +
                                " (" + order.formatOrderItems() + ")" +
                                " completed. please collect by typing 'collect'!");
                        out.flush();
                    }
                }

                @Override
                public void onOrderRepurposed(String itemType, String fromCustomer, String fromLocation,
                                              String toCustomer, String toLocation) {
                    if (out != null) {
                        String message = String.format("1 %s %s for %s has been transferred to %s's %s",
                                itemType, fromLocation, fromCustomer, toCustomer, toLocation);
                        out.println(message);
                        out.flush();
                    }
                }

            });
        }

    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(8888)) {
            System.out.println("Starting server on port 8888...");

            // store tea and coffee threads
            List<Thread> brewingThreads = new ArrayList<>();

            for (int i = 0; i < 2; i++) {
                Thread teaThread = new Thread(() -> brewDrink(true));
                Thread coffeeThread = new Thread(() -> brewDrink(false));
                brewingThreads.add(teaThread);
                brewingThreads.add(coffeeThread);
                teaThread.start();
                coffeeThread.start();
            }

            AtomicBoolean isRunning = new AtomicBoolean(true);

            Thread shutdownThread = new Thread(() -> {
                try {
                    System.out.println("server closing...");

                    isRunning.set(false);

                    serverSocket.close();

                    // interrupt brewing threads and ensure they finish safely
                    brewingThreads.forEach(Thread::interrupt);

                    for (Thread thread : brewingThreads) {
                        try {
                            thread.join(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                } catch (IOException e) {
                    System.err.println("error: " + e.getMessage());
                }
            });

            while (isRunning.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    // initiate new customer connection
                    CustomerSession session = new CustomerSession(clientSocket);
                    new Thread(() -> {
                        try {
                            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                            session.initialiseConnection(in);
                            session.handleCustomerRegistration(in);
                            session.processCustomerInput(in);
                        } catch (IOException ignored) {
                        } finally {
                            session.disconnectClient();
                        }
                    }).start();
                } catch (IOException e) {
                    if (isRunning.get()) {
                        System.err.println("error: " + e.getMessage());
                    }
                }
            }

            // proceed to close the main method
            shutdownThread.start();

        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
        }
    }

    private static void brewDrink(boolean isTea) {
        Semaphore slots = isTea ? teaSlots : coffeeSlots;
        int brewTime = isTea ? 30000 : 45000;

        while (true) {
            try {
                // attempt to acquire one of four available slots
                slots.acquire();
                Order.OrderItem item = removeFromWaitingArea(isTea);

                // proceed to brew if the customer hasn't cancelled an order
                if (item != null && !item.isCancelled()) {

                    try {
                        // lock the brewing area to add a drink
                        brewingLock.lock();
                        try {
                            item.setToBrewing();
                            brewingArea.add(item);
                            updateBrewing(isTea ? 1 : 0, isTea ? 0 : 1);
                        } finally {
                            brewingLock.unlock();
                        }

                        // sleep for 30s for tea, 45s for coffee
                        Thread.sleep(brewTime);

                        // proceed to handle the drink the customer did not cancel during brewing
                        if (!item.isCancelled()) {
                            brewingLock.lock();
                            try {
                                brewingArea.remove(item);
                                updateBrewing(isTea ? -1 : 0, isTea ? 0 : -1);
                            } finally {
                                brewingLock.unlock();
                            }

                            // lock tray area to transfer drink
                            trayLock.lock();
                            try {
                                item.setToTray();
                                CustomerInfo currentOwner = item.getOrder().getCustomer();
                                trayArea.computeIfAbsent(currentOwner, k -> new ArrayList<>()).add(item);
                                updateTray(isTea ? 1 : 0, isTea ? 0 : 1);

                                synchronized (trayArea) {
                                    List<Order.OrderItem> customerTrayItems =
                                            trayArea.getOrDefault(currentOwner, new ArrayList<>());
                                    if (customerTrayItems.size() == item.getOrder().getItems().size()) {
                                        // send order completion information to client
                                        OrderListeners.notifyOrderCompleted(item.getOrder());
                                    }
                                }
                            } finally {
                                // unlock to follow brewing cycle and prevent deadlocks
                                trayLock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        //
                        brewingLock.lock();
                        try {
                            if (brewingArea.remove(item)) {
                                updateBrewing(isTea ? -1 : 0, isTea ? 0 : -1);
                            }
                        } finally {
                            brewingLock.unlock();
                        }
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                // release slots when brewing is complete
                slots.release();
            }
        }
    }

    public static void addToWaitingArea(Order order, int teas, int coffees, boolean isNewOrder) {
        synchronized (waitingArea) {
            List<Order.OrderItem> itemsToAdd;

            if (isNewOrder) {
                itemsToAdd = order.getItems();
            } else {
                // to collect items and append them onto an existing order
                itemsToAdd = new ArrayList<>();
                for (int i = 0; i < teas; i++) {
                    itemsToAdd.add(new Order.OrderItem(true, order));
                }
                for (int i = 0; i < coffees; i++) {
                    itemsToAdd.add(new Order.OrderItem(false, order));
                }
            }

            waitingArea.addAll(itemsToAdd);
            updateWaiting(teas, coffees);
        }
    }

    private static Order.OrderItem removeFromWaitingArea(boolean isTea) {
        synchronized (waitingArea) {
            for (int i = 0; i < waitingArea.size(); i++) {
                Order.OrderItem item = waitingArea.get(i);
                if (isTea ? item.isTea() : item.isCoffee()) {
                    waitingArea.remove(i);
                    if (isTea) {
                        updateWaiting(-1, 0);
                    } else {
                        updateWaiting(0, -1);
                    }
                    return item;
                }
            }
            return null;
        }
    }

    public static boolean collectOrder(Order order) {

        synchronized (trayArea) {
            List<Order.OrderItem> trayItems = trayArea.get(order.getCustomer());

            if (trayItems == null || trayItems.isEmpty()) {
                return false;
            }
            if (trayItems.stream().anyMatch(Order.OrderItem::isCancelled)) {
                return false;
            }

            if (trayItems.size() != order.getItems().size()) {
                return false;
            }

            int teas = 0, coffees = 0;
            for (Order.OrderItem item : trayItems) {
                if (item.isTea()) teas++;
                else coffees++;
            }

            updateTray(-teas, -coffees);
            trayArea.remove(order.getCustomer());

            // permits the customer to make a new order
            order.setReadyForCollection(false);

            return true;
        }
    }

    public static String getOrderStatus(Order order) {
        StringBuilder status = new StringBuilder();

        List<Order.OrderItem> waitingItems;
        List<Order.OrderItem> brewingItems;
        List<Order.OrderItem> trayItems;

        synchronized (waitingArea) {
            waitingItems = waitingArea.stream()
                    .filter(item -> !item.isCancelled() &&
                            item.getOrder().equals(order) &&
                            item.isWaiting())
                    .toList();
        }

        synchronized (brewingArea) {
            brewingItems = brewingArea.stream()
                    .filter(item -> !item.isCancelled() &&
                            item.getOrder().equals(order) &&
                            item.isBrewing())
                    .toList();
        }

        synchronized (trayArea) {
            List<Order.OrderItem> customerTray = trayArea.get(order.getCustomer());
            trayItems = (customerTray != null) ? customerTray.stream()
                    .filter(item -> !item.isCancelled() &&
                            item.getOrder().equals(order) &&
                            item.isOnTray())
                    .toList() : new ArrayList<>();
        }

        status.append("order status for ").append(order.getCustomer().getCustomerName()).append(":");

        if (!waitingItems.isEmpty()) {
            status.append("\n- ");
            Order temp = new Order(null,
                    (int) waitingItems.stream().filter(Order.OrderItem::isTea).count(),
                    (int) waitingItems.stream().filter(Order.OrderItem::isCoffee).count());
            status.append(temp.formatOrderItems()).append(" in waiting area");
        }

        if (!brewingItems.isEmpty()) {
            status.append("\n- ");
            Order temp = new Order(null,
                    (int) brewingItems.stream().filter(Order.OrderItem::isTea).count(),
                    (int) brewingItems.stream().filter(Order.OrderItem::isCoffee).count());
            status.append(temp.formatOrderItems()).append(" currently brewing");
        }

        if (!trayItems.isEmpty()) {
            status.append("\n- ");
            Order temp = new Order(null,
                    (int) trayItems.stream().filter(Order.OrderItem::isTea).count(),
                    (int) trayItems.stream().filter(Order.OrderItem::isCoffee).count());
            status.append(temp.formatOrderItems()).append(" on the tray");
        }

        return status.toString();
    }

    public static void setClientWaiting(int delta) {
        synchronized (waitingCustomers) {
            waitingCustomers.addAndGet(delta);
            logState();
        }
    }

    public static void updateWaiting(int teas, int coffees) {
        waitingTeas.addAndGet(teas);
        waitingCoffees.addAndGet(coffees);
        logState();
    }

    public static void updateBrewing(int teas, int coffees) {
        brewingTeas.addAndGet(teas);
        brewingCoffees.addAndGet(coffees);
        logState();
    }

    public static void updateTray(int teas, int coffees) {
        trayTeas.addAndGet(teas);
        trayCoffees.addAndGet(coffees);
        logState();
    }

    public static void logState() {
        StringBuilder state = new StringBuilder("\n-+-+-+-+-+-+-+-+-+\n");

        state.append("clients in café: ").append(totalCustomers.get()).append("\n");
        state.append("clients waiting: ").append(waitingCustomers.get()).append("\n");

        state.append("waiting area: ");
        if (waitingTeas.get() > 0 || waitingCoffees.get() > 0) {
            if (waitingTeas.get() > 0) {
                state.append(waitingTeas.get()).append(" tea").append(waitingTeas.get() != 1 ? "s" : "");
                if (waitingCoffees.get() > 0) state.append(" and ");
            }
            if (waitingCoffees.get() > 0) {
                state.append(waitingCoffees.get()).append(" coffee").append(waitingCoffees.get() != 1 ? "s" : "");
            }
        } else {
            state.append("empty");
        }
        state.append("\n");

        state.append("brewing area: ");
        if (brewingTeas.get() > 0 || brewingCoffees.get() > 0) {
            if (brewingTeas.get() > 0) {
                state.append(brewingTeas.get()).append(" tea").append(brewingTeas.get() != 1 ? "s" : "");
                if (brewingCoffees.get() > 0) state.append(" and ");
            }
            if (brewingCoffees.get() > 0) {
                state.append(brewingCoffees.get()).append(" coffee").append(brewingCoffees.get() != 1 ? "s" : "");
            }
        } else {
            state.append("empty");
        }
        state.append("\n");

        state.append("tray area: ");
        if (trayTeas.get() > 0 || trayCoffees.get() > 0) {
            if (trayTeas.get() > 0) {
                state.append(trayTeas.get()).append(" tea").append(trayTeas.get() != 1 ? "s" : "");
                if (trayCoffees.get() > 0) state.append(" and ");
            }
            if (trayCoffees.get() > 0) {
                state.append(trayCoffees.get()).append(" coffee").append(trayCoffees.get() != 1 ? "s" : "");
            }
        } else {
            state.append("empty");
        }
        state.append("\n");

        state.append("-+-+-+-+-+-+-+-+-+");
        System.out.println(state);

        StateLogger.log(new StateLogger.State(
                totalCustomers,
                waitingCustomers,
                new StateLogger.DrinkCount(waitingTeas, waitingCoffees),
                new StateLogger.DrinkCount(brewingTeas, brewingCoffees),
                new StateLogger.DrinkCount(trayTeas, trayCoffees)
        ));
    }

    private static boolean acquireLocks() {
        // sequence of lock acquisition to prevent deadlocking in fine grained concurrency
        try {
            if (!disconnectionLock.tryLock(2, TimeUnit.SECONDS)) {
                return false;
            }
            if (!waitingLock.tryLock(1, TimeUnit.SECONDS)) {
                disconnectionLock.unlock();
                return false;
            }
            if (!brewingLock.tryLock(1, TimeUnit.SECONDS)) {
                waitingLock.unlock();
                disconnectionLock.unlock();
                return false;
            }
            if (!trayLock.tryLock(1, TimeUnit.SECONDS)) {
                brewingLock.unlock();
                waitingLock.unlock();
                disconnectionLock.unlock();
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void cleanupWaitingArea(CustomerInfo customerInfo) {
        synchronized (waitingArea) {
            // no filtering for non-repurposed items, that only applies to the brewing and tray areas
            List<Order.OrderItem> itemsToRemove = waitingArea.stream()
                    .filter(item -> item.getOrder().getCustomer().equals(customerInfo))
                    .toList();

            if (!itemsToRemove.isEmpty()) {
                int teas = Order.countItemsByType(itemsToRemove, true);
                int coffees = Order.countItemsByType(itemsToRemove, false);
                waitingArea.removeAll(itemsToRemove);
                updateWaiting(-teas, -coffees);
            }
        }
    }

    private static void cleanupBrewingArea(CustomerInfo customerInfo) {
        synchronized (brewingArea) {
            // filtering to remove non-repurposed items
            List<Order.OrderItem> itemsToRemove = brewingArea.stream()
                    .filter(item -> item.getOrder().getCustomer().equals(customerInfo) &&
                            !item.isRepurposed())
                    .toList();

            if (!itemsToRemove.isEmpty()) {
                int teas = Order.countItemsByType(itemsToRemove, true);
                int coffees = Order.countItemsByType(itemsToRemove, false);

                itemsToRemove.forEach(brewingArea::remove);
                updateBrewing(-teas, -coffees);

                // release slots for teas or coffees in the waiting area to acquire
                for (int i = 0; i < teas; i++) {
                    teaSlots.release();
                }
                for (int i = 0; i < coffees; i++) {
                    coffeeSlots.release();
                }
            }
        }
    }

    private static void cleanupTrayArea(CustomerInfo customerInfo) {
        synchronized (trayArea) {
            List<Order.OrderItem> items = trayArea.get(customerInfo);
            if (items != null && !items.isEmpty()) {
                // filter to not include repurposed items
                int teas = (int) items.stream()
                        .filter(item -> !item.isRepurposed() && item.isTea())
                        .count();
                int coffees = (int) items.stream()
                        .filter(item -> !item.isRepurposed() && item.isCoffee())
                        .count();

                updateTray(-teas, -coffees);

                items.removeIf(item -> !item.isRepurposed());


                if (items.isEmpty()) {
                    trayArea.remove(customerInfo);
                }
            }
        }
    }


    private static Map<Order, List<Order.OrderItem>> findRepurposeableItems(CustomerInfo disconnectingCustomer) {
        Map<Order, List<Order.OrderItem>> repurposeMap = new HashMap<>();
        List<Order.OrderItem> availableItems = new ArrayList<>();

        // collect brewing items from a leaving customer that are repurposeable
        brewingLock.lock();
        try {
            List<Order.OrderItem> brewingItems = brewingArea.stream()
                    .filter(item -> {
                        boolean isCustomerItem = item.getOrder().getCustomer().equals(disconnectingCustomer);
                        boolean canBeRepurposed = !item.isRepurposed();
                        return isCustomerItem && canBeRepurposed;
                    })
                    .toList();
            availableItems.addAll(brewingItems);
        } finally {
            brewingLock.unlock();
        }

        // collect tray items from a leaving customer that are repurposeable
        trayLock.lock();
        try {
            List<Order.OrderItem> trayItems = trayArea.get(disconnectingCustomer);
            if (trayItems != null) {
                List<Order.OrderItem> availableTrayItems = trayItems.stream()
                        .filter(item -> !item.isRepurposed())
                        .toList();
                availableItems.addAll(availableTrayItems);
            }
        } finally {
            trayLock.unlock();
        }

        // return early if no repurposable items are available
        if (availableItems.isEmpty()) {
            return repurposeMap;
        }

        // find customers who waiting for repurposable items
        waitingLock.lock();
        try {
            Map<CustomerInfo, Map<Boolean, Integer>> customerNeeds = new HashMap<>();

            for (Order.OrderItem item : waitingArea) {
                boolean isValidWaitingItem = !item.getOrder().getCustomer().equals(disconnectingCustomer)
                        && !item.isCancelled();

                // obtain counts of teas and/or coffees needed for the customer
                if (isValidWaitingItem) {
                    CustomerInfo customer = item.getOrder().getCustomer();
                    customerNeeds.computeIfAbsent(customer, k -> new HashMap<>())
                            .merge(item.isTea(), 1, Integer::sum);
                }
            }

            // attempt to match repurposable items with needs of customers
            for (Map.Entry<CustomerInfo, Map<Boolean, Integer>> entry : customerNeeds.entrySet()) {
                CustomerInfo customer = entry.getKey();
                Map<Boolean, Integer> needs = entry.getValue();

                List<Order.OrderItem> itemsToRepurpose = new ArrayList<>();

                // check items against the customer
                for (Order.OrderItem item : availableItems) {
                    boolean itemNeeded = needs.getOrDefault(item.isTea(), 0) > 0;
                    boolean canBeRepurposed = !item.isRepurposed();

                    if (itemNeeded && canBeRepurposed) {
                        itemsToRepurpose.add(item);
                        needs.merge(item.isTea(), -1, Integer::sum);
                        item.setRepurposed(true);
                    }
                }

                if (!itemsToRepurpose.isEmpty()) {
                    // identify items on the customer's waiting area to repurpose
                    Order receivingOrder = waitingArea.stream()
                            .map(Order.OrderItem::getOrder)
                            .filter(order -> order.getCustomer().equals(customer))
                            .findFirst()
                            .orElse(null);

                    if (receivingOrder != null) {
                        repurposeMap.put(receivingOrder, itemsToRepurpose);
                    }
                }
            }
        } finally {
            waitingLock.unlock();
        }

        return repurposeMap;
    }

    private static void repurposeItems(Map<Order, List<Order.OrderItem>> repurposeMap) {
        if (repurposeMap.isEmpty()) {
            return;
        }

        // simpler version of acquireLocks, to prevent deadlocks in fine grained concurrency
        waitingLock.lock();
        brewingLock.lock();
        trayLock.lock();

        try {
            for (Map.Entry<Order, List<Order.OrderItem>> entry : repurposeMap.entrySet()) {
                Order receivingOrder = entry.getKey();
                List<Order.OrderItem> itemsToRepurpose = entry.getValue();

                for (Order.OrderItem item : itemsToRepurpose) {
                    CustomerInfo originalCustomer = item.getOrder().getCustomer();

                    // check if a current customer can accept an item to repurpose
                    if (receivingOrder.canRepurposeItem(item)) {
                        // remove the waiting area item from the current customer's order for reassignment to the brewing/tray item
                        boolean removedWaiting = waitingArea.removeIf(waitingItem ->
                                waitingItem.getOrder().equals(receivingOrder) &&
                                        waitingItem.isTea() == item.isTea() &&
                                        !waitingItem.isCancelled());

                        if (removedWaiting) {
                            updateWaiting(item.isTea() ? -1 : 0, item.isTea() ? 0 : -1);
                        }

                        // reassign ownership of the item to the customer currently in the cafe
                        receivingOrder.repurposeItem(item);

                        // update brewing item with new customer reference to repurpose it
                        if (item.isBrewing()) {
                            int index = brewingArea.indexOf(item);
                            if (index != -1) {
                                brewingArea.set(index, item);
                            }
                        }

                        OrderListeners.notifyOrderRepurposed(
                                item.isTea() ? "tea" : "coffee",
                                originalCustomer.getCustomerName(),
                                item.isBrewing() ? "currently brewing" : "in tray",
                                receivingOrder.getCustomer().getCustomerName(),
                                item.isBrewing() ? "order" : "tray"
                        );
                    }
                }
            }
        } finally {
            trayLock.unlock();
            brewingLock.unlock();
            waitingLock.unlock();
        }
    }

    public static void handleClientDisconnection(CustomerInfo customerInfo, Order order, AtomicInteger clientCount) {
        if (order != null) {
            try {
                if (!acquireLocks()) {
                    return;
                }

                try {
                    // identify items that can be repurposed
                    Map<Order, List<Order.OrderItem>> repurposeMap = findRepurposeableItems(customerInfo);

                    if (!repurposeMap.isEmpty()) {
                        repurposeItems(repurposeMap);
                    }

                    // identify items that cannot be repurposed
                    order.getItems().stream()
                            .filter(item -> !item.isRepurposed())
                            .forEach(item -> item.setCancelled(true));

                    // remove all items that cannot be repurposed
                    cleanupWaitingArea(customerInfo);
                    cleanupBrewingArea(customerInfo);
                    cleanupTrayArea(customerInfo);

                    setClientWaiting(-1);

                } finally {
                    // opposite of acquireLocks, unlock to follow fine grained concurrency
                    trayLock.unlock();
                    brewingLock.unlock();
                    waitingLock.unlock();
                    disconnectionLock.unlock();
                }
            } catch (Exception ignored) {}
        }
        clientCount.decrementAndGet();
        logState();
    }

}