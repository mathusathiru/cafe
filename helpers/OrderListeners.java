package helpers;

import java.util.HashMap;
import java.util.Map;

public class OrderListeners {
    private static final Map<CustomerInfo, OrderListener> orderListeners = new HashMap<>();
    private static final Object lock = new Object();

    public interface OrderListener {
        void onOrderCompleted(Order order);
        void onOrderRepurposed(String itemType, String fromCustomer, String fromLocation,
                               String toCustomer, String toLocation);
    }

    public static void registerListener(CustomerInfo customer, OrderListener listener) {
        synchronized (lock) {
            orderListeners.put(customer, listener);
        }
    }

    public static void notifyOrderCompleted(Order order) {
        synchronized (lock) {
            if (order.hasEmptyItems()) {
                return;
            }

            OrderListener listener = orderListeners.get(order.getCustomer());
            if (listener != null) {
                order.setReadyForCollection(true);
                listener.onOrderCompleted(order);
            }
        }
    }

    public static void notifyOrderRepurposed(String itemType, String fromCustomer, String fromLocation,
                                             String toCustomer, String toLocation) {
        synchronized (lock) {
            OrderListener listener = null;
            for (Map.Entry<CustomerInfo, OrderListener> entry : orderListeners.entrySet()) {
                if (entry.getKey().getCustomerName().equals(toCustomer)) {
                    listener = entry.getValue();
                    break;
                }
            }

            if (listener != null) {
                listener.onOrderRepurposed(itemType, fromCustomer, fromLocation,
                        toCustomer, toLocation);
            }
        }
    }

}