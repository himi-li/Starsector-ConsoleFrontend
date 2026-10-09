package org.dsh.frontend;

import java.lang.reflect.Method;

/**
 * 反射工具：用于访问 Starsector 未公开的 UI 内部方法。
 * 所有方法在失败时返回 null / false，绝不向调用方抛异常。
 */
public final class Reflect {

    private Reflect() {
    }

    public static Method findMethod(Class<?> type, String name, Object[] args) {
        if (type == null || name == null) {
            return null;
        }
        Object[] a = args == null ? new Object[0] : args;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Method m = match(c.getDeclaredMethods(), name, a);
            if (m != null) {
                return m;
            }
            for (Class<?> itf : c.getInterfaces()) {
                Method im = findMethod(itf, name, a);
                if (im != null) {
                    return im;
                }
            }
        }
        return null;
    }

    private static Method match(Method[] methods, String name, Object[] args) {
        for (Method m : methods) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != args.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (args[i] == null) {
                    continue;
                }
                if (!wrap(ps[i]).isAssignableFrom(args[i].getClass())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return m;
            }
        }
        return null;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) {
            return Integer.class;
        }
        if (c == long.class) {
            return Long.class;
        }
        if (c == double.class) {
            return Double.class;
        }
        if (c == float.class) {
            return Float.class;
        }
        if (c == boolean.class) {
            return Boolean.class;
        }
        if (c == short.class) {
            return Short.class;
        }
        if (c == byte.class) {
            return Byte.class;
        }
        if (c == char.class) {
            return Character.class;
        }
        return c;
    }

    public static Object invoke(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Object[] a = args == null ? new Object[0] : args;
            Method m = findMethod(target.getClass(), name, a);
            if (m == null) {
                return null;
            }
            return m.invoke(target, a);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object get(Object target, String name) {
        return invoke(target, name);
    }

    public static Object getStatic(String className, String name) {
        try {
            Class<?> c = Class.forName(className);
            Method m = findMethod(c, name, new Object[0]);
            if (m == null) {
                return null;
            }
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean hasMethodOfName(Object target, String name) {
        if (target == null) {
            return false;
        }
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
        }
        return false;
    }

    public static Object findChildWithMethod(Object panel, String methodName) {
        Object children = invoke(panel, "getChildrenCopy");
        if (!(children instanceof Iterable)) {
            return null;
        }
        for (Object child : (Iterable<?>) children) {
            if (hasMethodOfName(child, methodName)) {
                return child;
            }
        }
        return null;
    }

    public static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
