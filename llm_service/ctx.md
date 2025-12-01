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
    AnotherCell another;
}

public class Input {
    public static boolean test(Object car) {
        // if (val == 0) {
        //     return false;
        // }
        // return true;
        // return ArrayUtils.contains(arr, val);
        if (car == null || !(car instanceof Car)) {
            return false;
        }
        Car carObj = (Car) car;
        if (carObj.wheels_num == 4) {
            if (carObj.highest_speed > 200) {
                return true;
            }
        }
        if (carObj.a == null) {
            return false;
        }
        return false;
    }

    public static int f(int left, int right) {
        return left + right;
    }

    public static void testMe(Cell cell, int x) {
        if (x > 0) {
            if (cell != null) {
                if (cell.another != null) {
                    if (f(x, cell.another.p + cell.another.q) == cell.i) {
                        System.out.println("success");
                    }
                } else {
                    System.out.println("cell.another is null");
                }
            } else {
                System.out.println("cell is null");
            }
        }
    }

    public static void testInstanceof(Object obj, int x) {
        if (obj == null) {
            System.out.println("obj is null");
        }
        if (x > 0) {
            System.out.println("x > 0");
            if (x > 10) {
                System.out.println("x > 10");
            } else {
                System.out.println("x <= 10");
            }
        } else {
            System.out.println("x <= 0");
            if (x < -10) {
                System.out.println("x < -10");
            } else {
                System.out.println("x >= -10");
            }
        }
        if (obj instanceof Car) {
            System.out.println("obj is Car");
            Car car = (Car) obj;
            if (car.wheels_num == 4) {
                System.out.println("car has 4 wheels");
            } else {
                System.out.println("car does not have 4 wheels");
            }
        } else {
            System.out.println("obj is not Car");
            System.out.println("obj class: " + (obj != null ? obj.getClass().getName() : "null"));
        }
    }

    public static void testPolymorphic(Object obj) {
        if (obj == null) {
            System.out.println("obj is null");
            return;
        }
        if (obj instanceof Car) {
            System.out.println("obj is Car");
        } else {
            System.out.println("obj is not Car");
            System.out.println("obj class: " + (obj != null ? obj.getClass().getName() : "null"));
        }
        return;
    }

    public static void testOtherApis(Integer a, int b, int c) {
        // int max = Math.max(a, b);
        // if (max < c) {
        //     System.out.println("max < c");
        // } else {
        //     System.out.println("max >= c");
        // }
        if (a.intValue() > 0) {
            System.out.println("a > 0");
        } else {
            System.out.println("a <= 0");
        }
    }

    public static void testString(String s) {
        if (s == null || s.isEmpty()) {
            System.out.println("String is null or empty");
            return;
        }
        if (s.length() > 5) {
            System.out.println("String length > 5");
        } else {
            System.out.println("String length <= 5");
        }
    }

    public static void testInt(int a, int b) {
        System.out.println("----------------------Starting testInt-------------------");
        if (a > b) {
            System.out.println("a > b");
            if (a - b > 10) {
                if (a - b > 20) {
                    if (a - b > 30 && a > 0 && b > 0) {
                        System.out.println("a - b > 30");
                    } else if (a < 0) {
                        System.out.println("a <= 0");
                    } else {
                        System.out.println("10 < a - b <= 20 or 20 < a - b <= 30");
                    }
                    System.out.println("a - b > 20");
                } else {
                    System.out.println("10 < a - b <= 20");
                }
                System.out.println("a - b > 10");
            } else {
                System.out.println("a - b <= 10");
            }
        } else {
            System.out.println("a <= b");
            if (b - a > 10) {
                System.out.println("b - a > 10");
            } else {
                System.out.println("b - a <= 10");
            }
        }
        System.out.println("----------------------Ending testInt-------------------");
    }

    public static void main(String[] args) {
        // test(new int[]{1,2,3}, 2);
        Car car = new Car();
        A a = new A();
        a.x = 10;
        car.a = a;
        car.wheels_num = 4;
        car.highest_speed = 250;
        System.out.println("Hello World");

        // test(car);
        // testInstanceof(car, 0);

        testPolymorphic(car);

        // testOtherApis(0, 0, 0);

        // String str = "Hello";
        // testString(str);

        // testInt(10, -2);

        System.out.println("Finished");
    }
}