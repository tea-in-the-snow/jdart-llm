// import org.apache.commons.lang3.ArrayUtils;

class A {
    int x;
}

class Car {
    int wheels_num;
    int highest_speed;
    A a;
}

class AnotherCell {
    int p;
    int q;
}

class Cell {
    int i;
    AnotherCell a;
}

class Animal implements animalInterface {
    @Override
    public void makeSound() {
        System.out.println("Animal sound!!!!!");
    }
}

class Dog extends Animal {
    @Override
    public void makeSound() {
        System.out.println("Woof!!!!!");
    }
}

class Cat extends Animal {
    @Override
    public void makeSound() {
        System.out.println("Meow!!!!!");
    }
}

class Node {
    int value;
    Node next;
}

    public static void testInvokeVirtual(Object a) {
        if (a == null) {
            System.out.println("a is null");
            return;
        }
        if (!(a instanceof Animal)) {
            System.out.println("a is not an Animal");
            return;
        }
        Animal animal = (Animal) a;
        animal.makeSound();
        // if (animal instanceof Dog) {
        // System.out.println("a is a Dog");
        // } else if (animal instanceof Cat) {
        // System.out.println("a is a Cat");
        // } else {
        // System.out.println("a is some other Animal");
        // }
    }
    public static boolean linkedListHasCycle(Node head) {
        if (head == null) {
            return false;
        }
        System.out.println("Checking linked list for cycles");
        Node slow = head;
        Node fast = head;
        while (fast != null && fast.next != null) {
            System.out.println("Visiting nodes: slow=" + slow.value + ", fast=" + fast.value);
            slow = slow.next;
            fast = fast.next.next;
            if (slow == fast) {
                return true; // Cycle detected
            }
        }
        return false; // No cycle
    }
    public static void testInterface(animalInterface ani) {
        if (ani == null) {
            System.out.println("ani is null");
            return;
        }
        ani.makeSound();
    }
