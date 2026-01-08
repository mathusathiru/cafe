# Virtual Café

This multi-thread client server application stimulates a real-life café through a terminal. This is composed of two primary elements, the customer (the client in this client server application) and the barista (the server in this client server application). Customers can place orders, check the current status, and collect them when ready, where a single customer can be run in one terminal window or multiple customers across individual terminal windows. Customers can order tea or coffee, taking 30 and 45 seconds to brew respectively. The barista tracks the orders across waiting, brewing and tray areas, and notifies customers when orders are complete. 

## Features

- Concurrent customer connections, enabled by allocating one terminal per customer
- Server handles separate waiting, brewing and tray areas for customer orders
- Orders can be updated at any time before collection to add more items
- Orders can be repurposed for waiting customers if customers leave during brewing, or once items are on the tray
- Customers can leave at any time, either by typing a command and handled through `Ctrl+C` interrupts
- State changes in the cafe recorded in the server and a JSON file (the Gson JSON library, gson.jar, is included in the .zip file)

## Setup

1. Download and extract the *CE303_2103812_ProgrammingAssignment.zip*  zip file

2. Open a new terminal in the extracted directory

## Instructions

1. Compile the Java files:

`javac -cp ".:gson.jar" *.java`

2. Start the barista server:

`java -cp ".:gson.jar" Barista`

3. Run one or more customer clients (in separate terminals):

`java Customer`

4. Interact with the café by entering commands in the customer terminal(s):

- Place an order: e.g. `order 2 teas and 1 coffee`  
- Check order status: `order status`
- Collect a completed order: `collect`
- Leave the café: `exit` or `Ctrl+C`

5. View status logs in the terminal or in `cafe_log.json`

## Issues and Limitations

- Requires Java 17 or later to compile and run 
- Hardcoded to use port 8888 and localhost
