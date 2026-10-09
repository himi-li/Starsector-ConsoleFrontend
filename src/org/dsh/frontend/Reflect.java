package org.dsh.frontend;

/**
 * 反射工具：访问 Starsector 未公开的 UI 内部方法。
 *
 * <p><b>为什么不能自己写反射</b>（实测结论）：游戏对 mod 脚本施加了
 * SecurityManager 限制，报错为
 * {@code SecurityException: File access and reflection are not allowed to scripts}。
 * 实测连 {@code Class.getMethods()}（public 方法枚举）都会被拒绝，
 * 自建 {@code MethodHandles.lookup().findVirtual(...)} 也会失败（日志显示 handle=null）。
 * 因此「自己写一套反射」这条路在本环境是走不通的。
 *
 * <p><b>可行方案</b>：直接复用 Console Commands 的
 * {@code org.lazywizard.console.overlay.v2.misc.ReflectionUtils}。它同样用
 * MethodHandle，但已在同一沙箱下被验证可用 —— Console Commands 正是用它挂载自己的
 * V2 控制台面板。本 mod 的 mod_info.json 已声明依赖 lw_console，因此该类必定可用。
 *
 * <p>这里采用<b>编译期直接调用</b>（不是再用反射去够它），避免多一层被拦截的风险；
 * 所有调用都包在 try/catch(Throwable) 里，即使 Console Commands 缺失也只会退化为
 * 功能不可用，不会让 mod 崩溃。
 */
public final class Reflect {

    /** Console Commands 的反射工具类名（用于诊断输出）。 */
    private static final String LC = "org.lazywizard.console.overlay.v2.misc.ReflectionUtils";

    private Reflect() {
    }

    public static String status() {
        try {
            Class.forName(LC);
            return "Console Commands ReflectionUtils 可用";
        } catch (Throwable t) {
            return "Console Commands ReflectionUtils 不可用: " + t;
        }
    }

    /**
     * 调用目标对象上的方法。
     *
     * @param target 目标对象
     * @param name   方法名
     * @param args   参数
     * @return 返回值；任何失败都返回 null
     */
    public static Object invoke(Object target, String name, Object... args) {
        if (target == null || name == null) {
            return null;
        }
        Object[] a = args == null ? new Object[0] : args;
        try {
            return org.lazywizard.console.overlay.v2.misc.ReflectionUtils.invoke(name, target, a, null, null);
        } catch (Throwable t) {
            warn("ReflectionUtils.invoke 失败 (" + name + "): " + t);
            return null;
        }
    }

    /** 等价于无参 invoke。 */
    public static Object get(Object target, String name) {
        return invoke(target, name);
    }

    /** 读取实例字段。 */
    public static Object getFieldValue(Object target, String fieldName) {
        if (target == null || fieldName == null) {
            return null;
        }
        try {
            return org.lazywizard.console.overlay.v2.misc.ReflectionUtils.get(fieldName, target, null);
        } catch (Throwable t) {
            warn("ReflectionUtils.get 失败 (" + fieldName + "): " + t);
            return null;
        }
    }

    public static boolean hasMethodOfName(Object target, String name) {
        if (target == null || name == null) {
            return false;
        }
        try {
            return org.lazywizard.console.overlay.v2.misc.ReflectionUtils.INSTANCE.hasMethodOfName(name, target);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在面板的子组件里找第一个含指定方法的（用于定位占位对话框）。 */
    public static Object findChildWithMethod(Object panel, String methodName) {
        Object children = invoke(panel, "getChildrenCopy");
        if (children == null) {
            children = invoke(panel, "getCopy");
        }
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

    /**
     * 查找界面主面板（screenPanel）。
     *
     * <p>刻意不做 instanceof 判断：脚本类加载器与 API 类加载器可能不同，
     * 同一个接口会有两个 Class 对象，instanceof 会误判为 false。
     */
    public static Object findScreenPanel(Object state) {
        if (state == null) {
            return null;
        }
        return invoke(state, "getScreenPanel");
    }

    /** 诊断用：状态 + 候选信息。 */
    public static String describePanelCandidates(Object state) {
        StringBuilder sb = new StringBuilder();
        sb.append("策略=").append(status());
        if (state == null) {
            sb.append(" | state=null");
            return sb.toString();
        }
        sb.append(" | state=").append(state.getClass().getName());
        return sb.toString();
    }

    public static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static void warn(String msg) {
        try {
            com.fs.starfarer.api.Global.getLogger(Reflect.class).warn("[ConsoleFrontend] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
