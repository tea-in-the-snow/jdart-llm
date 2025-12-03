class Animal {
    void makeSound() {
        System.out.println("Animal sound");
    }
}

class Dog extends Animal {
    @Override
    void makeSound() {
        System.out.println("Woof!!!");
    }
}

class Cat extends Animal {
    @Override
    void makeSound() {
        System.out.println("Meow!!!");
    }
}