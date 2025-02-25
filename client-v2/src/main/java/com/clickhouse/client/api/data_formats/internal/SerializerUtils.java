package com.clickhouse.client.api.data_formats.internal;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ClientException;
import com.clickhouse.client.api.query.POJOSetter;
import com.clickhouse.data.ClickHouseAggregateFunction;
import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.format.BinaryStreamUtils;
import com.clickhouse.data.value.ClickHouseBitmap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;

public class SerializerUtils {

    private static final Logger LOG = LoggerFactory.getLogger(SerializerUtils.class);

    public static void serializeData(OutputStream stream, Object value, ClickHouseColumn column) throws IOException {
        //Serialize the value to the stream based on the data type
        switch (column.getDataType()) {
            case Array:
                serializeArrayData(stream, value, column);
                break;
            case Tuple:
                serializeTupleData(stream, value, column);
                break;
            case Map:
                serializeMapData(stream, value, column);
                break;
            case AggregateFunction:
                serializeAggregateFunction(stream, value, column);
                break;
            default:
                serializePrimitiveData(stream, value, column);
                break;

        }
    }

    private static void serializeArrayData(OutputStream stream, Object value, ClickHouseColumn column) throws IOException {
        //Serialize the array to the stream
        //The array is a list of values
        List<?> values = (List<?>) value;
        writeVarInt(stream, values.size());
        for (Object val : values) {
            if (column.getArrayBaseColumn().isNullable()) {
                if (val == null) {
                    writeNull(stream);
                    continue;
                }
                writeNonNull(stream);
            }
            serializeData(stream, val, column.getArrayBaseColumn());
        }
    }

    private static void serializeTupleData(OutputStream stream, Object value, ClickHouseColumn column) throws IOException {
        //Serialize the tuple to the stream
        //The tuple is a list of values
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            for (int i = 0; i < values.size(); i++) {
                serializeData(stream, values.get(i), column.getNestedColumns().get(i));
            }
        } else if (value instanceof Object[]) {
            Object[] values = (Object[]) value;
            for (int i = 0; i < values.length; i++) {
                serializeData(stream, values[i], column.getNestedColumns().get(i));
            }
        } else {
            throw new IllegalArgumentException("Cannot serialize " + value + " as a tuple");
        }
    }

    private static void serializeMapData(OutputStream stream, Object value, ClickHouseColumn column) throws IOException {
        //Serialize the map to the stream
        //The map is a list of key-value pairs
        Map<?, ?> map = (Map<?, ?>) value;
        writeVarInt(stream, map.size());
        map.forEach((key, val) -> {
            try {
                serializePrimitiveData(stream, key, Objects.requireNonNull(column.getKeyInfo()));
                serializeData(stream, val, Objects.requireNonNull(column.getValueInfo()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void serializePrimitiveData(OutputStream stream, Object value, ClickHouseColumn column) throws IOException {
        //Serialize the value to the stream based on the type
        switch (column.getDataType()) {
            case Int8:
                BinaryStreamUtils.writeInt8(stream, convertToInteger(value));
                break;
            case Int16:
                BinaryStreamUtils.writeInt16(stream, convertToInteger(value));
                break;
            case Int32:
                BinaryStreamUtils.writeInt32(stream, convertToInteger(value));
                break;
            case Int64:
                BinaryStreamUtils.writeInt64(stream, convertToLong(value));
                break;
            case Int128:
                BinaryStreamUtils.writeInt128(stream, convertToBigInteger(value));
                break;
            case Int256:
                BinaryStreamUtils.writeInt256(stream, convertToBigInteger(value));
                break;
            case UInt8:
                BinaryStreamUtils.writeUnsignedInt8(stream, convertToInteger(value));
                break;
            case UInt16:
                BinaryStreamUtils.writeUnsignedInt16(stream, convertToInteger(value));
                break;
            case UInt32:
                BinaryStreamUtils.writeUnsignedInt32(stream, convertToLong(value));
                break;
            case UInt64:
                BinaryStreamUtils.writeUnsignedInt64(stream, convertToLong(value));
                break;
            case UInt128:
                BinaryStreamUtils.writeUnsignedInt128(stream, convertToBigInteger(value));
                break;
            case UInt256:
                BinaryStreamUtils.writeUnsignedInt256(stream, convertToBigInteger(value));
                break;
            case Float32:
                BinaryStreamUtils.writeFloat32(stream, (Float) value);
                break;
            case Float64:
                BinaryStreamUtils.writeFloat64(stream, (Double) value);
                break;
            case Decimal:
            case Decimal32:
            case Decimal64:
            case Decimal128:
            case Decimal256:
                BinaryStreamUtils.writeDecimal(stream, (BigDecimal) value, column.getPrecision(), column.getScale());
                break;
            case Bool:
                BinaryStreamUtils.writeBoolean(stream, (Boolean) value);
                break;
            case String:
                BinaryStreamUtils.writeString(stream, convertToString(value));
                break;
            case FixedString:
                BinaryStreamUtils.writeFixedString(stream, convertToString(value), column.getPrecision());
                break;
            case Date:
                writeDate(stream, value, ZoneId.of("UTC")); // TODO: check
                break;
            case Date32:
                writeDate32(stream, value, ZoneId.of("UTC")); // TODO: check
                break;
            case DateTime: {
                ZoneId zoneId = column.getTimeZone() == null ? ZoneId.of("UTC") : column.getTimeZone().toZoneId();
                writeDateTime(stream, value, zoneId);
                break;
            }
            case DateTime64: {
                ZoneId zoneId = column.getTimeZone() == null ? ZoneId.of("UTC") : column.getTimeZone().toZoneId();
                writeDateTime64(stream, value, column.getScale(), zoneId);
                break;
            }
            case UUID:
                BinaryStreamUtils.writeUuid(stream, (UUID) value);
                break;
            case Enum8:
                BinaryStreamUtils.writeEnum8(stream, (Byte) value);
                break;
            case Enum16:
                BinaryStreamUtils.writeEnum16(stream, convertToInteger(value));
                break;
            case IPv4:
                BinaryStreamUtils.writeInet4Address(stream, (Inet4Address) value);
                break;
            case IPv6:
                BinaryStreamUtils.writeInet6Address(stream, (Inet6Address) value);
                break;
            case JSON:
                serializeJSON(stream, value);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported data type: " + column.getDataType());
        }
    }

    private static void serializeJSON(OutputStream stream, Object value) throws IOException {
        if (value instanceof String) {
            BinaryStreamUtils.writeString(stream, (String)value);
        } else {
            throw new UnsupportedOperationException("Serialization of Java object to JSON is not supported yet.");
        }
    }

    private static void serializeAggregateFunction(OutputStream stream, Object value, ClickHouseColumn column) throws IOException {
        if (column.getAggregateFunction() == ClickHouseAggregateFunction.groupBitmap) {
            if (value == null) {
                throw new IllegalArgumentException("Cannot serialize null value for aggregate function: " + column.getAggregateFunction());
            } else if (value instanceof ClickHouseBitmap) {
                stream.write(((ClickHouseBitmap)value).toBytes()); // TODO: review toBytes() implementation - it can be simplified
            } else {
                throw new IllegalArgumentException("Cannot serialize value of type " + value.getClass() + " for aggregate function: " + column.getAggregateFunction());
            }
        } else {
            throw new UnsupportedOperationException("Unsupported aggregate function: " + column.getAggregateFunction());
        }
    }

    public static Integer convertToInteger(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            return Integer.parseInt((String) value);
        } else if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to Integer");
        }
    }

    public static Long convertToLong(Object value) {
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            return Long.parseLong((String) value);
        } else if (value instanceof Boolean) {
            return ((Boolean) value) ? 1L : 0L;
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to Long");
        }
    }

    public static BigInteger convertToBigInteger(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        } else if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof String) {
            return new BigInteger((String) value);
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to BigInteger");
        }
    }

    public static BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        } else if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        } else if (value instanceof String) {
            return new BigDecimal((String) value);
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to BigDecimal");
        }
    }

    public static String convertToString(Object value) {
        return java.lang.String.valueOf(value);
    }

    public static <T extends Enum<T>> Set<T> parseEnumList(String value, Class<T> enumType) {
        Set<T> values = new HashSet<>();
        for (StringTokenizer causes = new StringTokenizer(value, Client.VALUES_LIST_DELIMITER); causes.hasMoreTokens(); ) {
            values.add(Enum.valueOf(enumType, causes.nextToken()));
        }
        return values;
    }

    public static boolean numberToBoolean(Number value) {
        return value.doubleValue() != 0;
    }

    public static boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof BigInteger) {
            return ((BigInteger) value).compareTo(BigInteger.ZERO) != 0;
        } else if (value instanceof BigDecimal) {
            return ((BigDecimal) value).compareTo(BigDecimal.ZERO) != 0;
        } else if (value instanceof Number) {
            return ((Number) value).longValue() != 0;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to Boolean");
        }
    }

    public static List<?> convertArrayValueToList(Object value) {
        if (value instanceof BinaryStreamReader.ArrayValue) {
            return ((BinaryStreamReader.ArrayValue) value).asList();
        } else if (value.getClass().isArray()) {
            return  Arrays.stream(((Object[]) value)).collect(Collectors.toList());
        } else if (value instanceof List) {
            return (List<?>) value;
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " ('" + value.getClass() + "') to list");
        }
    }

    public static POJOSetter compilePOJOSetter(Method setterMethod, ClickHouseColumn column) {
        Class<?> dtoClass = setterMethod.getDeclaringClass();

        // creating a new class to implement POJOSetter which will call the setter method to set column value
        final String pojoSetterClassName = (dtoClass.getName() + setterMethod.getName()).replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, ACC_PUBLIC, pojoSetterClassName
                , null, "java/lang/Object",
                new String[]{POJOSetter.class.getName().replace('.', '/')});


        // constructor method
        {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V");
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /*
         * Next code will generate instance of POJOSetter that will
         * call BinaryStreamReader.read* method to read value from stream
         *
         */
        // setter setValue(Object obj, Object value) impl
        {
            MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, "setValue",
                    Type.getMethodDescriptor(Type.VOID_TYPE,
                            Type.getType(Object.class), Type.getType(BinaryStreamReader.class),
                            Type.getType(ClickHouseColumn.class)), null, new String[]{"java/io/IOException"});

            Class<?> targetType = setterMethod.getParameterTypes()[0];
            Class<?> targetPrimitiveType = ClickHouseDataType.toPrimitiveType(targetType); // will return object class if no primitive


            mv.visitCode();
            mv.visitVarInsn(ALOAD, 1); // load target object
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(dtoClass));
            mv.visitVarInsn(ALOAD, 2); // load reader

            if (targetType.isPrimitive() && BinaryStreamReader.isReadToPrimitive(column.getDataType())) {
                binaryReaderMethodForType(mv,
                        targetPrimitiveType, column.getDataType());
            } else if (targetType.isPrimitive() && column.getDataType() == ClickHouseDataType.UInt64) {
                mv.visitTypeInsn(CHECKCAST, Type.getInternalName(BigInteger.class));
                mv.visitMethodInsn(INVOKEVIRTUAL,
                        Type.getInternalName(BigInteger.class),
                        targetType.getSimpleName() + "Value",
                        "()" + Type.getDescriptor(targetType),
                        false);
            } else {
                mv.visitVarInsn(ALOAD, 3); // column
                // load target class into stack
                mv.visitLdcInsn(Type.getType(targetType));
                // call readValue method
                mv.visitMethodInsn(INVOKEVIRTUAL,
                        Type.getInternalName(BinaryStreamReader.class),
                        "readValue",
                        Type.getMethodDescriptor(
                                Type.getType(Object.class),
                                Type.getType(ClickHouseColumn.class),
                                Type.getType(Class.class)),
                        false);

                if (targetType.isAssignableFrom(List.class) && column.getDataType() == ClickHouseDataType.Tuple) {
                    mv.visitTypeInsn(CHECKCAST, Type.getInternalName(Object[].class));
                    mv.visitMethodInsn(INVOKESTATIC,
                            Type.getInternalName(Arrays.class),
                            "asList",
                            Type.getMethodDescriptor(Type.getType(List.class), Type.getType(Object[].class)),
                            false);
                } else {
                    mv.visitTypeInsn(CHECKCAST, Type.getInternalName(targetType));
                    // cast to target type
                }
            }

            // finally call setter with the result of target class
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    Type.getInternalName(dtoClass),
                    setterMethod.getName(),
                    Type.getMethodDescriptor(setterMethod),
                    false);

            mv.visitInsn(RETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }

        try {
            SerializerUtils.DynamicClassLoader loader = new SerializerUtils.DynamicClassLoader(dtoClass.getClassLoader());
            Class<?> clazz = loader.defineClass(pojoSetterClassName.replace('/', '.'), writer.toByteArray());
            return (POJOSetter) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ClientException("Failed to compile setter for " + setterMethod.getName(), e);
        }
    }

    private static void binaryReaderMethodForType(MethodVisitor mv, Class<?> targetType, ClickHouseDataType dataType) {
        String readerMethod = null;
        String readerMethodReturnType = null;
        int convertOpcode = -1;

        switch (dataType) {
            case Int8:
                readerMethod = "readByte";
                readerMethodReturnType = Type.getDescriptor(byte.class);
                break;
            case UInt8:
                readerMethod = "readUnsignedByte";
                readerMethodReturnType = Type.getDescriptor(short.class);
                break;
            case Int16:
                readerMethod = "readShortLE";
                readerMethodReturnType = Type.getDescriptor(short.class);
                break;
            case UInt16:
                readerMethod = "readUnsignedShortLE";
                readerMethodReturnType = Type.getDescriptor(int.class);
                convertOpcode = intToOpcode(targetType);
                break;
            case Int32:
                readerMethod = "readIntLE";
                readerMethodReturnType = Type.getDescriptor(int.class);
                convertOpcode = intToOpcode(targetType);
                break;
            case UInt32:
                readerMethod = "readUnsignedIntLE";
                readerMethodReturnType = Type.getDescriptor(long.class);
                convertOpcode = longToOpcode(targetType);
                break;
            case Int64:
                readerMethod = "readLongLE";
                readerMethodReturnType = Type.getDescriptor(long.class);
                convertOpcode = longToOpcode(targetType);
                break;
            case Float32:
                readerMethod = "readFloatLE";
                readerMethodReturnType = Type.getDescriptor(float.class);
                convertOpcode = floatToOpcode(targetType);
                break;
            case Float64:
                readerMethod = "readDoubleLE";
                readerMethodReturnType = Type.getDescriptor(double.class);
                convertOpcode = doubleToOpcode(targetType);
                break;
            case Enum8:
                readerMethod = "readByte";
                readerMethodReturnType = Type.getDescriptor(byte.class);
                break;
            case Enum16:
                readerMethod = "readShortLE";
                readerMethodReturnType = Type.getDescriptor(short.class);
                break;
            default:
                throw new ClientException("Column type '" + dataType + "' cannot be set to a primitive type '" + targetType + "'");
        }

        mv.visitMethodInsn(INVOKEVIRTUAL,
                Type.getInternalName(BinaryStreamReader.class),
                readerMethod,
                "()" +readerMethodReturnType,
                false);
        if (convertOpcode != -1) {
            mv.visitInsn(convertOpcode);
        }
    }

    private static int intToOpcode(Class<?> targetType) {
        if (targetType == short.class) {
            return Opcodes.I2S;
        } else if (targetType == long.class) {
            return Opcodes.I2L;
        } else if (targetType == byte.class) {
            return Opcodes.I2B;
        } else if (targetType == char.class) {
            return Opcodes.I2C;
        } else if (targetType == float.class) {
            return Opcodes.I2F;
        } else if (targetType == double.class) {
            return Opcodes.I2D;
        }
        return -1;
    }

    private static int longToOpcode(Class<?> targetType) {
        if (targetType == int.class) {
            return Opcodes.L2I;
        } else if (targetType == float.class) {
            return Opcodes.L2F;
        } else if (targetType == double.class) {
            return Opcodes.L2D;
        }
        return -1;
    }

    private static int floatToOpcode(Class<?> targetType) {
        if (targetType == int.class) {
            return Opcodes.F2I;
        } else if (targetType == long.class) {
            return Opcodes.F2L;
        } else if (targetType == double.class) {
            return Opcodes.F2D;
        }
        return -1;
    }

    private static int doubleToOpcode(Class<?> targetType) {
        if (targetType == int.class) {
            return Opcodes.D2I;
        } else if (targetType == long.class) {
            return Opcodes.D2L;
        } else if (targetType == float.class) {
            return Opcodes.D2F;
        }
        return -1;
    }

    public static class DynamicClassLoader extends ClassLoader {

        public DynamicClassLoader(ClassLoader classLoader) {
            super(classLoader);
        }
        public Class<?> defineClass(String name, byte[] code) throws ClassNotFoundException {
            return super.defineClass(name, code, 0, code.length);
        }
    }

    public static void writeVarInt(OutputStream output, long value) throws IOException {
        // reference code https://github.com/ClickHouse/ClickHouse/blob/abe314feecd1647d7c2b952a25da7abf5c19f352/src/IO/VarInt.h#L187
        for (int i = 0; i < 9; i++) {
            byte b = (byte) (value & 0x7F);

            if (value > 0x7F) {
                b |= 0x80;
            }

            output.write(b);
            value >>= 7;

            if (value == 0) {
                return;
            }
        }
    }

    public static void writeNull(OutputStream output) throws IOException {
        writeBoolean(output, true);
    }

    public static void writeNonNull(OutputStream output) throws IOException {
        writeBoolean(output, false);
    }

    public static void writeBoolean(OutputStream output, boolean value) throws IOException {
        output.write(value ? 1 : 0);
    }

    public static void writeDate(OutputStream output, Object value, ZoneId targetTz) throws IOException {
        int epochDays = 0;
        if (value instanceof LocalDate) {
            LocalDate d = (LocalDate) value;
            epochDays = (int) d.atStartOfDay(targetTz).toLocalDate().toEpochDay();
        } else if (value instanceof ZonedDateTime) {
            ZonedDateTime dt = (ZonedDateTime) value;
            epochDays = (int)dt.withZoneSameInstant(targetTz).toLocalDate().toEpochDay();
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to Long");
        }
        BinaryStreamUtils.writeUnsignedInt16(output, epochDays);
    }

    public static void writeDate32(OutputStream output, Object value, ZoneId targetTz) throws IOException {
        int epochDays= 0;
        if (value instanceof LocalDate) {
            LocalDate d = (LocalDate) value;
            epochDays = (int) d.atStartOfDay(targetTz).toLocalDate().toEpochDay();
        } else if (value instanceof ZonedDateTime) {
            ZonedDateTime dt = (ZonedDateTime) value;
            epochDays =  (int)dt.withZoneSameInstant(targetTz).toLocalDate().toEpochDay();
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to Long");
        }

        BinaryStreamUtils.writeInt32(output, epochDays);
    }

    public static void writeDateTime32(OutputStream output, Object value, ZoneId targetTz) throws IOException {
        writeDateTime(output, value, targetTz);
    }

    public static void writeDateTime(OutputStream output, Object value, ZoneId targetTz) throws IOException {
        long ts = 0;
        if (value instanceof LocalDateTime) {
            LocalDateTime dt = (LocalDateTime) value;
            ts = dt.atZone(targetTz).toEpochSecond();
        } else if (value instanceof ZonedDateTime) {
            ZonedDateTime dt = (ZonedDateTime) value;
            ts = dt.withZoneSameInstant(targetTz).toEpochSecond();
        } else if (value instanceof Timestamp) {
            Timestamp t = (Timestamp) value;
            ts = t.toLocalDateTime().atZone(targetTz).toEpochSecond();
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to DataTime");
        }

        BinaryStreamUtils.writeUnsignedInt32(output, ts);
    }

    public static void writeDateTime64(OutputStream output, Object value, int scale, ZoneId targetTz) throws IOException {
        if (scale < 0 || scale > 9) {
            throw new IllegalArgumentException("Invalid scale value '" + scale + "'");
        }

        long ts = 0;
        long nano = 0;
        if (value instanceof LocalDateTime) {
            ZonedDateTime dt = ((LocalDateTime) value).atZone(targetTz);
            ts = dt.toEpochSecond();
            nano = dt.getNano();
        } else if (value instanceof ZonedDateTime) {
            ZonedDateTime dt = ((ZonedDateTime) value).withZoneSameInstant(targetTz);
            ts = dt.toEpochSecond();
            nano = dt.getNano();
        } else if (value instanceof Timestamp) {
            ZonedDateTime dt = ((Timestamp) value).toLocalDateTime().atZone(targetTz);
            ts = dt.toEpochSecond();
            nano = dt.getNano();
        } else {
            throw new IllegalArgumentException("Cannot convert " + value + " to DataTime");
        }

        ts *= BinaryStreamReader.BASES[scale];
        if (nano > 0L) {
            ts += nano / BinaryStreamReader.BASES[9 - scale];
        }

        BinaryStreamUtils.writeInt64(output, ts);
    }
}
