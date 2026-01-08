package helpers;

import java.util.concurrent.atomic.AtomicInteger;

public class CustomerInfo {
    private static final AtomicInteger idGenerator = new AtomicInteger(0);
    private final int customerID;
    private final String customerName;

    public CustomerInfo(String customerName) {
        this.customerID = idGenerator.incrementAndGet();
        this.customerName = customerName;
    }

    public String getCustomerName() {
        return customerName;
    }

    @Override
    public String toString() {
        return customerName + " (id: " + customerID + ")";
    }
}