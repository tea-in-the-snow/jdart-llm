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