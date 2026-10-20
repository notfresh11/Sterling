package net.sterling.bridge.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.function.Supplier;

public class TextHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(TextHelper.class);

    private static Class<?> textClass;
    private static Method literalMethod;

    static {
        String[] candidateClasses = new String[]{
                "net.minecraft.text.Text",
                "net.minecraft.network.chat.Component",
                "net.minecraft.class_2561"
        };

        for (String className : candidateClasses) {
            try {
                textClass = Class.forName(className);
                LOGGER.info("Successfully loaded Text/Component class: {}", className);
                break;
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (textClass != null) {
            String[] candidateMethods = new String[]{"literal", "of", "method_43470", "nullToEmpty"};
            for (String methodName : candidateMethods) {
                try {
                    literalMethod = textClass.getMethod(methodName, String.class);
                    LOGGER.info("Found text creation method: {}.{}()", textClass.getName(), methodName);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
    }

    public static Object createText(String message) {
        if (textClass == null || literalMethod == null) {
            return message;
        }
        try {
            return literalMethod.invoke(null, message);
        } catch (Exception e) {
            LOGGER.error("Failed to create Text object for message: {}", message, e);
            return message;
        }
    }

    public static void disconnectPlayer(Object handler, String message) {
        Object textObj = createText(message);
        if (handler == null) return;

        Class<?> handlerClass = handler.getClass();
        for (Method m : handlerClass.getMethods()) {
            if (m.getName().equals("disconnect") || m.getName().equals("method_14364") || m.getName().startsWith("disconnect")) {
                if (m.getParameterCount() == 1) {
                    try {
                        m.invoke(handler, textObj);
                        LOGGER.info("Successfully disconnected player with message.");
                        return;
                    } catch (Exception e) {
                        LOGGER.error("Error invoking disconnect on handler", e);
                    }
                }
            }
        }
    }

    public static void sendFeedback(Object source, String message) {
        Object textObj = createText(message);
        if (source == null) return;

        Class<?> sourceClass = source.getClass();
        for (Method m : sourceClass.getMethods()) {
            if (m.getName().equals("sendFeedback") || m.getName().equals("method_9226")) {
                try {
                    if (m.getParameterCount() == 2) {
                        Class<?> firstParam = m.getParameterTypes()[0];
                        if (Supplier.class.isAssignableFrom(firstParam)) {
                            m.invoke(source, (Supplier<?>) () -> textObj, false);
                        } else {
                            m.invoke(source, textObj, false);
                        }
                        return;
                    }
                } catch (Exception e) {
                    LOGGER.error("Error sending feedback to source", e);
                }
            }
        }
    }
}
