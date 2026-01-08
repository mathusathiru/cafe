package helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// handles a single customer order
public class Order {
    // handles a single item
    public static class OrderItem {
        private enum Type { TEA, COFFEE }
        private enum Status { WAITING, BREWING, TRAY }

        private final Type type;
        private volatile Status status;
        private volatile boolean cancelled = false;
        private volatile boolean repurposed = false;
        private volatile Order order;

        public OrderItem(boolean isTea, Order order) {
            this.type = isTea ? Type.TEA : Type.COFFEE;
            this.order = order;
            setToWaiting();
        }

        public boolean isTea() { return type == Type.TEA; }
        public boolean isCoffee() { return type == Type.COFFEE; }

        public boolean isWaiting() { return status == Status.WAITING; }
        public boolean isBrewing() { return status == Status.BREWING; }
        public boolean isOnTray() { return status == Status.TRAY; }

        public synchronized void setToWaiting() { status = Status.WAITING; }
        public synchronized void setToBrewing() { status = Status.BREWING; }
        public synchronized void setToTray() { status = Status.TRAY; }

        public Order getOrder() {
            return order;
        }

        public synchronized void updateOrder(Order newOrder) {
            this.order = newOrder;
        }

        // orders are cancelled if a customer leaves the cafe
        public synchronized void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public synchronized void setRepurposed(boolean repurposed) {
            this.repurposed = repurposed;
        }

        public boolean isRepurposed() {
            return repurposed;
        }

        @Override
        public String toString() {
            return (isTea() ? "tea" : "coffee") + " (" +
                    (isWaiting() ? "waiting" :
                            isBrewing() ? "brewing" : "tray") + ")";
        }

        // ensure items added to an order are recognised as equally as original items in an order
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrderItem other)) return false;
            return type == other.type &&
                    order.getCustomer().equals(other.order.getCustomer());
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, order.getCustomer());
        }
    }

    private final CustomerInfo customer;
    private final List<OrderItem> items;
    private volatile boolean readyForCollection = false;
    private final Object lock = new Object();

    public Order(CustomerInfo customer, int teas, int coffees) {
        this.customer = customer;
        this.items = new ArrayList<>();
        addItems(teas, coffees);
    }

    public CustomerInfo getCustomer() { return customer; }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public int getTeas() {
        return (int) items.stream().filter(OrderItem::isTea).count();
    }

    public int getCoffees() {
        return (int) items.stream().filter(OrderItem::isCoffee).count();
    }

    public boolean hasEmptyItems() {
        return items.isEmpty();
    }

    public void addItems(int teas, int coffees) {
        synchronized (lock) {
            List<OrderItem> newItems = new ArrayList<>();

            for (int i = 0; i < teas; i++) {
                OrderItem item = new OrderItem(true, this);
                newItems.add(item);
            }
            for (int i = 0; i < coffees; i++) {
                OrderItem item = new OrderItem(false, this);
                newItems.add(item);
            }

            items.addAll(newItems);
        }
    }

    public boolean isReadyForCollection() {
        return readyForCollection;
    }

    public void setReadyForCollection(boolean ready) {
        synchronized (lock) {
            this.readyForCollection = ready;
        }
    }

    public boolean canRepurposeItem(OrderItem item) {
        return items.stream()
                .anyMatch(orderItem ->
                        orderItem.isWaiting() &&
                                !orderItem.isCancelled() &&
                                orderItem.isTea() == item.isTea());
    }

    public void repurposeItem(OrderItem item) {
        items.removeIf(orderItem ->
                orderItem.isWaiting() &&
                        !orderItem.isCancelled() &&
                        orderItem.isTea() == item.isTea());

        items.add(item);
        item.updateOrder(this);
        item.setRepurposed(true);
    }

    public static int countItemsByType(List<OrderItem> items, boolean isTea) {
        return (int) items.stream()
                .filter(isTea ? OrderItem::isTea : OrderItem::isCoffee)
                .count();
    }

    public String formatOrderItems() {
        if (hasEmptyItems()) return "no items";

        StringBuilder sb = new StringBuilder();
        int teas = getTeas();
        int coffees = getCoffees();

        if (teas > 0) {
            sb.append(teas).append(" tea").append(teas > 1 ? "s" : "");
            if (coffees > 0) {
                sb.append(" and ");
            }
        }
        if (coffees > 0) {
            sb.append(coffees).append(" coffee").append(coffees > 1 ? "s" : "");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        if (hasEmptyItems()) return customer.getCustomerName() + ": empty order";
        return customer.getCustomerName() + ": " + formatOrderItems();
    }
}