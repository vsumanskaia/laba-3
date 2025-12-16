import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

// тут класс заказа
class Order {
    private final int id;
    private final int cookTime;
    private volatile boolean isReady = false;
    private final long startTime;
    private final String waiterName; // какой официант взял заказ
    private final Object deliveryLock = new Object(); // лок для доставки

    public Order(int id, int cookTime, String waiterName) {
        this.id = id;
        this.cookTime = cookTime;
        this.startTime = System.currentTimeMillis();
        this.waiterName = waiterName;
    }

    public int getId() { return id; }
    public int getCookTime() { return cookTime; }
    public boolean isReady() { return isReady; }
    public void setReady() { isReady = true; }
    public String getWaiterName() { return waiterName; }
    public Object getDeliveryLock() { return deliveryLock; }
}

// здесь у нас генератор ID заказов
class OrderIdGenerator {
    private static final AtomicInteger counter = new AtomicInteger(1);

    public static int generateId() {
        return counter.getAndIncrement();
    }

    public static int getTotal() {
        return counter.get() - 1;
    }

    public static void reset() {
        counter.set(1);
    }
}

// клиенты
class CustomerGenerator implements Runnable {
    private final BlockingQueue<Order> orderQueue; // очередь для новых заказов
    private volatile boolean running = true;
    private final Statistics stats;

    public CustomerGenerator(BlockingQueue<Order> orderQueue, Statistics stats) {
        this.orderQueue = orderQueue;
        this.stats = stats;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        System.out.println("[Клиенты] Начали приходить...");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // тут периодически генерируем новый заказ
                Order order = generateOrder();
                stats.recordOrderCreated(orderQueue.size());

                // кладем заказ в очередь для официантов
                orderQueue.put(order);

                System.out.printf("[%tH:%tM:%tS] Клиент создал заказ #%d (%d сек)\n",
                        new Date(), new Date(), new Date(), order.getId(), order.getCookTime());

                // случайная задержка между появлением клиентов
                Thread.sleep(ThreadLocalRandom.current().nextInt(1000, 3000));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[Клиенты] Перестали приходить");
    }

    private Order generateOrder() {
        int orderId = OrderIdGenerator.generateId();
        int cookTime = ThreadLocalRandom.current().nextInt(2, 7);
        return new Order(orderId, cookTime, null);
    }
}

// пункт со статистикой
class Statistics {
    private final AtomicInteger ordersCreated = new AtomicInteger(0);
    private final AtomicInteger ordersCompleted = new AtomicInteger(0);
    private final AtomicInteger maxQueueSize = new AtomicInteger(0);
    private long startTime;
    private long endTime;

    public void start() {
        startTime = System.currentTimeMillis();
    }

    public void stop() {
        endTime = System.currentTimeMillis();
    }

    public void recordOrderCreated(int queueSize) {
        ordersCreated.incrementAndGet();
        if (queueSize > maxQueueSize.get()) {
            maxQueueSize.set(queueSize);
        }
    }

    public void recordOrderCompleted() {
        ordersCompleted.incrementAndGet();
    }

    public void printStats(String mode, int chefs, int waiters, int queue) {
        long totalTime = (endTime - startTime) / 1000;

        System.out.println("\n" + "=".repeat(50));
        System.out.println("ИТОГОВАЯ СТАТИСТИКА");
        System.out.println("=".repeat(50));
        System.out.println("Режим: " + mode);
        System.out.println("Параметры: Повара: " + chefs + ", Официанты: " + waiters + ", Очередь: " + queue);
        System.out.printf("Время работы: %d сек\n", totalTime);
        System.out.printf("Всего заказов создано: %d\n", ordersCreated.get());
        System.out.printf("Заказов выполнено: %d\n", ordersCompleted.get());
        System.out.printf("Не выполнено: %d\n", ordersCreated.get() - ordersCompleted.get());
        System.out.printf("Максимальная очередь клиентов: %d\n", maxQueueSize.get());
    }
}

// официанты
class Waiter implements Runnable {
    private final String name;
    private final BlockingQueue<Order> customerQueue; // очередь от клиентов
    private final BlockingQueue<Order> kitchenQueue;  // очередь на кухню
    private final Map<Integer, Order> activeOrders; // заказы в работе
    private final Object lock;
    private volatile boolean working = true;
    private int ordersTaken = 0;
    private int ordersDelivered = 0;
    private final Statistics stats;

    public Waiter(String name, BlockingQueue<Order> customerQueue, BlockingQueue<Order> kitchenQueue,
                  Map<Integer, Order> activeOrders, Object lock, Statistics stats) {
        this.name = name;
        this.customerQueue = customerQueue;
        this.kitchenQueue = kitchenQueue;
        this.activeOrders = activeOrders;
        this.lock = lock;
        this.stats = stats;
    }

    public void stop() {
        working = false;
    }

    public String getStats() {
        return String.format("%s: принял %d, доставил %d", name, ordersTaken, ordersDelivered);
    }

    @Override
    public void run() {
        System.out.println(name + " начал работу");

        while (working && !Thread.currentThread().isInterrupted()) {
            try {
                // сначала принять заказ от клиента (из очереди клиентов)
                acceptOrderFromCustomer();

                // потом проверить готовые заказы и доставить
                deliverReadyOrders();

                // затем короткая пауза
                Thread.sleep(100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println(name + " закончил работу");
    }

    private void acceptOrderFromCustomer() throws InterruptedException {
        // здесь мы пытаемся взять заказ из очереди клиентов
        Order order = customerQueue.poll(50, TimeUnit.MILLISECONDS);

        if (order != null) {
            // создаем новый заказ с назначением этого официанта
            Order assignedOrder = new Order(order.getId(), order.getCookTime(), this.name);
            ordersTaken++;

            System.out.printf("[%tH:%tM:%tS] %s принял заказ #%d от клиента\n",
                    new Date(), new Date(), new Date(), name, assignedOrder.getId());

            // поместить заказ в очередь кухни
            kitchenQueue.put(assignedOrder);

            // добавить в активные заказы для отслеживания
            synchronized (lock) {
                activeOrders.put(assignedOrder.getId(), assignedOrder);
            }

            System.out.printf("[%tH:%tM:%tS] %s отправил заказ #%d на кухню\n",
                    new Date(), new Date(), new Date(), name, assignedOrder.getId());
        }
    }

    private void deliverReadyOrders() {
        synchronized (lock) {
            // создаем копию ключей для безопасной итерации
            List<Integer> orderIds = new ArrayList<>(activeOrders.keySet());

            for (int orderId : orderIds) {
                Order order = activeOrders.get(orderId);
                if (order != null && order.isReady() && name.equals(order.getWaiterName())) {
                    synchronized (order.getDeliveryLock()) {
                        if (activeOrders.containsKey(orderId) && order.isReady()) {
                            activeOrders.remove(orderId);

                            // доставляем заказ
                            ordersDelivered++;
                            stats.recordOrderCompleted();

                            System.out.printf("[%tH:%tM:%tS] %s доставил заказ #%d клиенту\n",
                                    new Date(), new Date(), new Date(), name, order.getId());
                        }
                    }
                }
            }
        }
    }
}

// повора
class Chef implements Runnable {
    private final String name;
    private final BlockingQueue<Order> kitchenQueue; // очередь с кухни
    private final Map<Integer, Order> activeOrders;
    private final Object lock;
    private volatile boolean working = true;
    private int ordersCooked = 0;

    public Chef(String name, BlockingQueue<Order> kitchenQueue,
                Map<Integer, Order> activeOrders, Object lock) {
        this.name = name;
        this.kitchenQueue = kitchenQueue;
        this.activeOrders = activeOrders;
        this.lock = lock;
    }

    public void stop() {
        working = false;
    }

    public String getStats() {
        return String.format("%s: приготовил %d", name, ordersCooked);
    }

    @Override
    public void run() {
        System.out.println(name + " начал работу");

        while (working && !Thread.currentThread().isInterrupted()) {
            try {
                // берем заказ из очереди кухни
                Order order = kitchenQueue.poll(200, TimeUnit.MILLISECONDS);

                if (order != null) {
                    cookOrder(order);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println(name + " закончил работу");
    }

    private void cookOrder(Order order) {
        System.out.printf("[%tH:%tM:%tS] %s начал готовить заказ #%d (%d сек) для %s\n",
                new Date(), new Date(), new Date(), name, order.getId(), order.getCookTime(),
                order.getWaiterName() != null ? order.getWaiterName() : "неизвестного");

        try {
            Thread.sleep(order.getCookTime() * 1000L);
            ordersCooked++;

            // помечаем заказ как готовый
            order.setReady();

            System.out.printf("[%tH:%tM:%tS] %s приготовил заказ #%d для %s\n",
                    new Date(), new Date(), new Date(), name, order.getId(), order.getWaiterName());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// главный класс с меню (похожее делала и в прошлых лабах)
public class RestaurantSystem {
    private static final Scanner scanner = new Scanner(System.in);

    // ддефолтные настройки (константы)
    private static final int DEFAULT_CHEFS = 3;
    private static final int DEFAULT_WAITERS = 3;
    private static final int DEFAULT_QUEUE = 10;

    // текущие настройки (можно менять)
    private static int chefCount = DEFAULT_CHEFS;
    private static int waiterCount = DEFAULT_WAITERS;
    private static int queueSize = DEFAULT_QUEUE;

    public static void main(String[] args) {
        System.out.println("Привет! Давайте запустим симуляцию вместе!");
        while (true) {
            showMainMenu();
            int choice = getIntInput("Выберите режим: ", 1, 6);

            switch (choice) {
                case 1:
                    runSimulation("Быстрая симуляция", 15);
                    break;
                case 2:
                    runSimulation("Средняя симуляция", 30);
                    break;
                case 3:
                    runSimulation("Длительная симуляция", 60);
                    break;
                case 4:
                    runOrderCountSimulation();
                    break;
                case 5:
                    showSettingsMenu();
                    break;
                case 6:
                    System.out.println("Выход из программы");
                    scanner.close();
                    return;
            }

            System.out.print("\n Пожалйста нажмите Enter для продолжения!");
            scanner.nextLine();
        }
    }

    private static void showMainMenu() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("           СИМУЛЯЦИЯ РЕСТОРАНА");
        System.out.println("=".repeat(50));
        System.out.println("Текущие параметры: Повара: " + chefCount +
                ", Официанты: " + waiterCount + ", Очередь: " + queueSize);
        System.out.println("\n1. Быстрая симуляция (15 секунд)");
        System.out.println("2. Средняя симуляция (30 секунд)");
        System.out.println("3. Длительная симуляция (60 секунд)");
        System.out.println("4. Режим по количеству заказов");
        System.out.println("5. НАСТРОЙКИ ПАРАМЕТРОВ");
        System.out.println("6. Выход");
        System.out.println();
    }

    private static void showSettingsMenu() {
        int oldChefCount = chefCount;
        int oldWaiterCount = waiterCount;
        int oldQueueSize = queueSize;

        while (true) {
            System.out.println("\n" + "=".repeat(50));
            System.out.println("           НАСТРОЙКИ ПАРАМЕТРОВ");
            System.out.println("=".repeat(50));
            System.out.println("Текущие значения:");
            System.out.println("1. Количество поваров: " + chefCount);
            System.out.println("2. Количество официантов: " + waiterCount);
            System.out.println("3. Размер очереди: " + queueSize);
            System.out.println("\n4. Изменить количество поваров");
            System.out.println("5. Изменить количество официантов");
            System.out.println("6. Изменить размер очереди");
            System.out.println("7. Сбросить к стандартным (" + DEFAULT_CHEFS + ", " +
                    DEFAULT_WAITERS + ", " + DEFAULT_QUEUE + ")");
            System.out.println("8. Сохранить и вернуться в меню");
            System.out.println("9. Отменить изменения");
            System.out.println();

            int choice = getIntInput("Выберите пункт: ", 1, 9);

            switch (choice) {
                case 1:
                    System.out.println("Текущее количество поваров: " + chefCount);
                    break;
                case 2:
                    System.out.println("Текущее количество официантов: " + waiterCount);
                    break;
                case 3:
                    System.out.println("Текущий размер очереди: " + queueSize);
                    break;
                case 4:
                    System.out.print("Введите новое количество поваров: ");
                    chefCount = scanner.nextInt();
                    scanner.nextLine();
                    System.out.println("Количество поваров изменено на: " + chefCount);
                    break;
                case 5:
                    System.out.print("Введите новое количество официантов: ");
                    waiterCount = scanner.nextInt();
                    scanner.nextLine();
                    System.out.println("Количество официантов изменено на: " + waiterCount);
                    break;
                case 6:
                    System.out.print("Введите новый размер очереди: ");
                    queueSize = scanner.nextInt();
                    scanner.nextLine();
                    System.out.println("Размер очереди изменен на: " + queueSize);
                    break;
                case 7:
                    chefCount = DEFAULT_CHEFS;
                    waiterCount = DEFAULT_WAITERS;
                    queueSize = DEFAULT_QUEUE;
                    System.out.println("\nНастройки сброшены к стандартным:");
                    System.out.println("Повара: " + chefCount + ", Официанты: " + waiterCount +
                            ", Очередь: " + queueSize);
                    break;
                case 8:
                    System.out.println("\nНастройки сохранены!");
                    return;
                case 9:
                    chefCount = oldChefCount;
                    waiterCount = oldWaiterCount;
                    queueSize = oldQueueSize;
                    System.out.println("\nИзменения отменены!");
                    return;
            }
        }
    }

    private static void runSimulation(String mode, int durationSeconds) {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("ЗАПУСК: " + mode);
        System.out.println("Параметры: Повара: " + chefCount + ", Официанты: " + waiterCount + ", Очередь: " + queueSize);
        System.out.println("Длительность: " + durationSeconds + " секунд");
        System.out.println("=".repeat(50));

        Statistics stats = new Statistics();
        stats.start();

        RestaurantSimulation sim = new RestaurantSimulation(stats, chefCount, waiterCount, queueSize);
        sim.start();

        try {
            Thread.sleep(durationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        sim.stop();
        stats.stop();
        stats.printStats(mode, chefCount, waiterCount, queueSize);
        sim.printPersonnelStats();
    }

    private static void runOrderCountSimulation() {
        System.out.print("Введите количество заказов: ");
        int orderCount = scanner.nextInt();
        scanner.nextLine();

        System.out.println("\n" + "=".repeat(50));
        System.out.println("ЗАПУСК: Режим по количеству заказов");
        System.out.println("Параметры: Повара: " + chefCount + ", Официанты: " + waiterCount + ", Очередь: " + queueSize);
        System.out.println("Цель: " + orderCount + " заказов");
        System.out.println("=".repeat(50));

        Statistics stats = new Statistics();
        stats.start();

        RestaurantSimulation sim = new RestaurantSimulation(stats, chefCount, waiterCount, queueSize);
        sim.start();

        // тут ждем пока наберется нужное количество заказов
        while (OrderIdGenerator.getTotal() < orderCount) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // даем время на выполнение оставшихся заказов
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        sim.stop();
        stats.stop();
        stats.printStats("По количеству заказов (" + orderCount + ")", chefCount, waiterCount, queueSize);
        sim.printPersonnelStats();
    }

    private static int getIntInput(String prompt, int min, int max) {
        int value;
        while (true) {
            System.out.print(prompt);
            try {
                value = scanner.nextInt();
                if (value >= min && value <= max) {
                    scanner.nextLine();
                    return value;
                }
                System.out.printf("Введите число от %d до %d!\n", min, max);
            } catch (InputMismatchException e) {
                System.out.println("Введите целое число!");
                scanner.nextLine();
            }
        }
    }
}

// класс для управления симуляцией
class RestaurantSimulation {
    private final Statistics stats;
    private final int chefCount;
    private final int waiterCount;
    private final int queueSize;

    private BlockingQueue<Order> customerQueue; // клиенты → официанты
    private BlockingQueue<Order> kitchenQueue;  // официанты → кухня
    private Map<Integer, Order> activeOrders;
    private Object lock;

    private CustomerGenerator customerGenerator;
    private Thread customerThread;

    private ExecutorService chefPool;
    private List<Chef> chefs;
    private List<Waiter> waiters;
    private List<Thread> waiterThreads;

    public RestaurantSimulation(Statistics stats, int chefCount, int waiterCount,
                                int queueSize) {
        this.stats = stats;
        this.chefCount = chefCount;
        this.waiterCount = waiterCount;
        this.queueSize = queueSize;
    }

    public void start() {
        OrderIdGenerator.reset();

        customerQueue = new LinkedBlockingQueue<>(queueSize * 2);
        kitchenQueue = new LinkedBlockingQueue<>(queueSize);
        activeOrders = Collections.synchronizedMap(new HashMap<>());
        lock = new Object();

        // сначала запускаем генератор клиентов
        customerGenerator = new CustomerGenerator(customerQueue, stats);
        customerThread = new Thread(customerGenerator);
        customerThread.start();

        // потом создаем поваров
        chefPool = Executors.newFixedThreadPool(chefCount);
        chefs = new ArrayList<>();

        for (int i = 1; i <= chefCount; i++) {
            Chef chef = new Chef("Повар-" + i, kitchenQueue, activeOrders, lock);
            chefs.add(chef);
            chefPool.execute(chef);
        }

        // создаем официантов
        waiters = new ArrayList<>();
        waiterThreads = new ArrayList<>();

        for (int i = 1; i <= waiterCount; i++) {
            Waiter waiter = new Waiter("Официант-" + i, customerQueue, kitchenQueue,
                    activeOrders, lock, stats);
            waiters.add(waiter);
            Thread thread = new Thread(waiter);
            waiterThreads.add(thread);
            thread.start();
        }
    }

    public void stop() {
        // останавливаем клиентов
        if (customerGenerator != null) {
            customerGenerator.stop();
        }
        if (customerThread != null) {
            customerThread.interrupt();
            try { customerThread.join(1000); } catch (InterruptedException ignored) {}
        }

        // останавливаем официантов
        for (Waiter waiter : waiters) waiter.stop();
        for (Thread thread : waiterThreads) {
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) {}
        }

        // останавливаем поваров
        for (Chef chef : chefs) chef.stop();
        chefPool.shutdownNow();

        try {
            chefPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void printPersonnelStats() {
        System.out.println("\nСТАТИСТИКА ПЕРСОНАЛА:");
        for (Chef chef : chefs) {
            System.out.println("  " + chef.getStats());
        }
        for (Waiter waiter : waiters) {
            System.out.println("  " + waiter.getStats());
        }

        System.out.println("\nОсталось заказов у клиентов: " + customerQueue.size());
        System.out.println("Осталось заказов на кухне: " + kitchenQueue.size());
        System.out.println("Заказов в обработке: " + activeOrders.size());
        System.out.println("\n" + "=".repeat(50));
    }
}