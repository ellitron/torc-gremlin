/* Copyright (c) 2015-2019 Stanford University
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR(S) DISCLAIM ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL AUTHORS BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package net.ellitron.torc.util;

import net.ellitron.torc.*;

import edu.stanford.ramcloud.RAMCloudObject;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 *
 * @author Jonathan Ellithorpe (jde@cs.stanford.edu)
 */
public class TorcHelper {

  public static Charset DEFAULT_CHAR_ENCODING = Charset.forName("UTF-8");

  public static void legalPropertyKeyValueArray(
      final Class<? extends Element> clazz,
      final Object... propertyKeyValues) throws IllegalArgumentException {
    if (propertyKeyValues.length % 2 != 0) {
      throw Element.Exceptions.providedKeyValuesMustBeAMultipleOfTwo();
    }
    for (int i = 0; i < propertyKeyValues.length; i = i + 2) {
      if (propertyKeyValues[i] == null) {
        throw Property.Exceptions.propertyKeyCanNotBeNull();
      }
      if (!(propertyKeyValues[i] instanceof String)
          && !(propertyKeyValues[i] instanceof T)) {
        throw Element.Exceptions
            .providedKeyValuesMustHaveALegalKeyOnEvenIndices();
      }
      if (propertyKeyValues[i] instanceof T) {
        if ((propertyKeyValues[i].equals(T.label))) {
          if (propertyKeyValues[i + 1] == null) {
            throw Element.Exceptions.labelCanNotBeNull();
          } else if (((String) propertyKeyValues[i + 1]).length() == 0) {
            throw Element.Exceptions.labelCanNotBeEmpty();
          }
        }
        if ((propertyKeyValues[i].equals(T.id))) {
          if (clazz.isAssignableFrom(Edge.class)) {
            throw Edge.Exceptions.userSuppliedIdsNotSupported();
          }
        }
      } else {
        if (((String) propertyKeyValues[i]).length() == 0) {
          throw Property.Exceptions.propertyKeyCanNotBeEmpty();
        }
        if (propertyKeyValues[i + 1] == null) {
          throw Property.Exceptions.propertyValueCanNotBeNull();
        }
        if (!(propertyKeyValues[i + 1] instanceof String)) {
          throw Property.Exceptions
              .dataTypeOfPropertyValueNotSupported(propertyKeyValues[i + 1]);
        }
      }
    }
  }

  /* Supported data types. */
  private enum TypeCode {
    INTEGER((byte)0x00),
    LONG((byte)0x01),
    STRING((byte)0x02),
    LIST((byte)0x03),
    MAP((byte)0x04);

    public static final int BYTES = 1;
    private final byte val;

    TypeCode(byte val) {
      this.val = val;
    }

    public byte val() {
      return val;
    }

    public static TypeCode valueOf(byte val) {
      switch(val) {
        case 0x00:
          return INTEGER;
        case 0x01:
          return LONG;
        case 0x02:
          return STRING;
        case 0x03:
          return LIST;
        case 0x04:
          return MAP;
        default:
          throw new RuntimeException(String.format(
                "Unrecognized TypeCode: %d", val));
      }
    }
  };

  /* 
   * Serialize supported data types into byte array. The returned byte array
   * always begins with a TypeCode encoding the type of the object in the
   * proceeding bytes and therefore how to parse it. If the data type is a
   * collection type, then the method calls itself recusively.
   */
  public static byte[] serializeObject(Object value) {
    if (value instanceof Integer) {
      ByteBuffer buffer = 
          ByteBuffer.allocate(TypeCode.BYTES + Integer.BYTES)
          .order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(TypeCode.INTEGER.val());
      buffer.putInt((Integer)value);
      return buffer.array();
    } else if (value instanceof Long) {
      ByteBuffer buffer = 
          ByteBuffer.allocate(TypeCode.BYTES + Long.BYTES)
          .order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(TypeCode.LONG.val());
      buffer.putLong((Long)value);
      return buffer.array();
    } else if (value instanceof String) {
      byte[] strBytes = ((String)value).getBytes(DEFAULT_CHAR_ENCODING);
      ByteBuffer buffer = 
          ByteBuffer.allocate(TypeCode.BYTES + Short.BYTES + strBytes.length)
          .order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(TypeCode.STRING.val());
      buffer.putShort((short)strBytes.length);
      buffer.put(strBytes);
      return buffer.array();
    } else if (value instanceof List) {
      List listValue = (List)value;
      List<byte[]> serElems = new ArrayList<>(listValue.size());
      int totalBytes = 0;
      for (int i = 0; i < listValue.size(); i++ ) {
        byte[] serElem = serializeObject(listValue.get(i));
        serElems.add(serElem);
        totalBytes += serElem.length;
      }
      ByteBuffer buffer = 
          ByteBuffer.allocate(TypeCode.BYTES + Short.BYTES + totalBytes)
          .order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(TypeCode.LIST.val());
      buffer.putShort((short)serElems.size());
      for (int i = 0; i < serElems.size(); i++ ) {
        buffer.put(serElems.get(i));
      }
      return buffer.array();
    } else if (value instanceof Map) {
      Map mapValue = (Map)value;
      List<byte[]> serEnts = new ArrayList<>(2 * mapValue.size());
      int totalBytes = 0;
      for (Map.Entry e : (Set<Map.Entry>)mapValue.entrySet()) {
        byte[] serKey = serializeObject(e.getKey());
        byte[] serVal = serializeObject(e.getValue());
        serEnts.add(serKey);
        serEnts.add(serVal);
        totalBytes += serKey.length + serVal.length;
      }
      ByteBuffer buffer = 
          ByteBuffer.allocate(TypeCode.BYTES + Short.BYTES + totalBytes)
          .order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(TypeCode.MAP.val());
      buffer.putShort((short)(serEnts.size()/2));
      for (int i = 0; i < serEnts.size(); i += 2 ) {
        buffer.put(serEnts.get(i));
        buffer.put(serEnts.get(i + 1));
      }
      return buffer.array();
    } else {
      throw new RuntimeException(String.format(
            "Unrecognized data type: %s. Unable to serialize.", 
            value.getClass()));
    }
  }

  /* 
   * ParseInfo is used as an OUT parameter to parsing functions that allow them
   * to return metadata about the parse to the caller.
   */
  public static class ParseInfo {
    public int length; // Number of bytes that were parsed.
    public ParseInfo() {
      this.length = 0;
    }
  }

  /* 
   * Take a byte array containing a serialized object and parse it out,
   * returning an instance of the object. Objects are always serialized in
   * LITTLE_ENDIAN byte order.
   */
  public static Object deserializeObject(byte[] value) {
    return deserializeObject(value, 0);
  }

  public static Object deserializeObject(byte[] value, int offset) {
    return deserializeObject(value, offset, new ParseInfo());
  }

  public static Object deserializeObject(byte[] value, int offset, 
      ParseInfo pinfo) {
    int subOffset;
    TypeCode type = TypeCode.valueOf(value[offset+0]);
    switch (type) {
      case INTEGER:
        int intVal = ((value[offset+1] & 0xFF) << 0) | 
                     ((value[offset+2] & 0xFF) << 8) | 
                     ((value[offset+3] & 0xFF) << 16) | 
                     ((value[offset+4] & 0xFF) << 24);
        pinfo.length = 5;
        return new Integer(intVal);
      case LONG:
        long longVal = ((long)(value[offset+1] & 0xFF) << 0) | 
                       ((long)(value[offset+2] & 0xFF) << 8) | 
                       ((long)(value[offset+3] & 0xFF) << 16) | 
                       ((long)(value[offset+4] & 0xFF) << 24) |
                       ((long)(value[offset+5] & 0xFF) << 32) | 
                       ((long)(value[offset+6] & 0xFF) << 40) |
                       ((long)(value[offset+7] & 0xFF) << 48) | 
                       ((long)(value[offset+8] & 0xFF) << 56);
        pinfo.length = 9;
        return new Long(longVal);
      case STRING:
        short strLen = (short)(((value[offset+1] & 0xFF) << 0) | 
                               ((value[offset+2] & 0xFF) << 8));
        pinfo.length = 3 + strLen;
        return new String(value, offset+3, strLen, DEFAULT_CHAR_ENCODING);
      case LIST:
        short size = (short)(((value[offset+1] & 0xFF) << 0) | 
                             ((value[offset+2] & 0xFF) << 8));
        List<Object> list = new ArrayList<>(size);
        subOffset = offset + 3;
        for (int i = 0; i < size; i++) {
          list.add(deserializeObject(value, subOffset, pinfo));
          subOffset += pinfo.length;
        }
        pinfo.length = subOffset - offset;
        return list;
      case MAP:
        short entries = (short)(((value[offset+1] & 0xFF) << 0) | 
                                ((value[offset+2] & 0xFF) << 8));
        Map<Object, Object> map = new HashMap<>(entries);
        subOffset = offset + 3;
        for (int i = 0; i < entries; i++) {
          Object key = deserializeObject(value, subOffset, pinfo);
          subOffset += pinfo.length;
          Object val = deserializeObject(value, subOffset, pinfo);
          subOffset += pinfo.length;
          map.put(key, val);
        }
        pinfo.length = subOffset - offset;
        return map;
      default:
        throw new RuntimeException(String.format(
              "Unrecognized data type: %s. Unable to serialize.", 
              type));
    }
  }

  public static enum VertexKeyType {

    LABEL,
    PROPERTIES,
    EDGE_LIST,
    EDGE_LABELS,
  }

  public static byte[] getVertexLabelKey(UInt128 vertexId) {
    ByteBuffer buffer = ByteBuffer.allocate(UInt128.BYTES + Byte.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putLong(vertexId.getUpperLong());
    buffer.putLong(vertexId.getLowerLong());
    buffer.put((byte) VertexKeyType.LABEL.ordinal());
    return buffer.array();
  }

  public static byte[] getVertexPropertiesKey(UInt128 vertexId) {
    ByteBuffer buffer = ByteBuffer.allocate(UInt128.BYTES + Byte.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putLong(vertexId.getUpperLong());
    buffer.putLong(vertexId.getLowerLong());
    buffer.put((byte) VertexKeyType.PROPERTIES.ordinal());
    return buffer.array();
  }

  public static VertexKeyType getVertexKeyType(byte[] key) {
    return VertexKeyType.values()[key[UInt128.BYTES]];
  }


  /**
   * Generates the key for the RAMCloud object that stores the list of edge
   * labels of all incident edges to the given vertex. This is useful
   * because edges are stored by edge label, direction, and neighbor vertex
   * label. Therefore, to get all the edges incident to a vertex, one must know
   * the incident edge labels (as well as the neighbor labels for each of those
   * edge labels). In normal queries, however, the edge label is specified and
   * there's no need to read this list.
   *
   * @param vertexId
   *
   * @return RAMCloud Key.
   */
  public static byte[] getIncidentEdgeLabelListKey(UInt128 vertexId) {
    return vertexId.toByteArray();
  }

  /**
   * Generates the key for the RAMCloud object that stores the list of vertex
   * labels of vertices that lie on the other side of edges with label
   * edgeLabel for vertex with ID vertexId and in direction dir. This is useful
   * because edges are stored by edge label, direction, and neighbor vertex
   * label. Therefore, to get all the edges given an edge label, one must know
   * all the vertex labels that lie on the other side of those edges.
   *
   * @param vertexId
   * @param edgeLabel
   * @param dir
   *
   * @return RAMCloud Key.
   */
  public static byte[] getNeighborLabelListKey(UInt128 vertexId, 
      String edgeLabel, Direction dir) {
    byte[] labelByteArray = serializeObject(edgeLabel);
    ByteBuffer buffer =
        ByteBuffer.allocate(UInt128.BYTES + Short.BYTES + labelByteArray.length
            + Byte.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putLong(vertexId.getUpperLong());
    buffer.putLong(vertexId.getLowerLong());
    buffer.putShort((short) labelByteArray.length);
    buffer.put(labelByteArray);
    buffer.put((byte) dir.ordinal());
    return buffer.array();
  }

  /**
   * Generates a key prefix that defines an exclusive key-space for this
   * combination of vertex ID, edge label, edge direction, and vertex label. No
   * other (vertex ID, elabel, direction, vlabel) combination will have a key
   * prefix that is a prefix of this key, or for which this key is a prefix of.
   * This key-prefix is used to generate unique keys for RAMCloud objects that
   * store the potentially multiple segments that make up an edge list.
   *
   * @param vertexId
   * @param edgeLabel
   * @param dir
   * @param vertexLabel
   *
   * @return RAMCloud Key.
   */
  public static byte[] getEdgeListKeyPrefix(UInt128 vertexId, String edgeLabel,
      Direction dir, String vertexLabel) {
    byte[] edgeLabelByteArray = serializeObject(edgeLabel);
    byte[] vertexLabelByteArray = serializeObject(vertexLabel);
    ByteBuffer buffer =
        ByteBuffer.allocate(UInt128.BYTES 
            + Short.BYTES + edgeLabelByteArray.length
            + Byte.BYTES 
            + Short.BYTES + vertexLabelByteArray.length)
        .order(ByteOrder.LITTLE_ENDIAN);
    appendEdgeListKeyPrefixToBuffer(vertexId, edgeLabelByteArray, dir, 
        vertexLabelByteArray, buffer);
    return buffer.array();
  }

  /**
   * Generates a key prefix that defines an exclusive key-space for this
   * combination of vertex ID, edge label, edge direction, and vertex label. No
   * other (vertex ID, elabel, direction, vlabel) combination will have a key
   * prefix that is a prefix of this key, or for which this key is a prefix of.
   * This key-prefix is used to generate unique keys for RAMCloud objects that
   * store the potentially multiple segments that make up an edge list.
   *
   * @param vertexId
   * @param edgeLabel
   * @param dir
   * @param vertexLabel
   * @param byteBuffer
   *
   * @return RAMCloud Key.
   */
  public static void appendEdgeListKeyPrefixToBuffer(UInt128 vertexId, 
      byte[] edgeLabelByteArray,
      Direction dir, 
      byte[] vertexLabelByteArray, 
      ByteBuffer buffer) {
    buffer.putLong(vertexId.getUpperLong());
    buffer.putLong(vertexId.getLowerLong());
    buffer.putShort((short) edgeLabelByteArray.length);
    buffer.put(edgeLabelByteArray);
    buffer.put((byte) dir.ordinal());
    buffer.putShort((short) vertexLabelByteArray.length);
    buffer.put(vertexLabelByteArray);
  }

  /** 
   * Take two traversal results and merge them. 
   *
   * @param a First traversal result
   * @param b Second traversal result
   * @param dedup Whether or not to dedup the lists in the values of the merge.
   *
   * @return Joined traversal result.
   */
  public static TraversalResult fuse(
      TraversalResult trA,
      TraversalResult trB,
      boolean dedup) {
    Map<TorcVertex, List<TorcVertex>> a = trA.vMap;
    Map<TorcVertex, List<TorcVertex>> b = trB.vMap;

    Map<TorcVertex, List<TorcVertex>> fusedMap = new HashMap<>(a.size());
    Set<TorcVertex> globalFusedSet = new HashSet<>();

    for (Map.Entry e : a.entrySet()) {
      TorcVertex aVertex = (TorcVertex)e.getKey();
      List<TorcVertex> aVertexList = (List<TorcVertex>)e.getValue();

      if (dedup) {
        Set<TorcVertex> fusedSet = new HashSet<>();
        for (TorcVertex v : aVertexList) {
          if (b.containsKey(v))
            fusedSet.addAll(b.get(v));
        }

        if (fusedSet.size() > 0) {
          fusedMap.put(aVertex, new ArrayList<>(fusedSet));
          globalFusedSet.addAll(fusedSet);
        }
      } else {
        List<TorcVertex> fusedList = new ArrayList<>();
        for (TorcVertex v : aVertexList) {
          if (b.containsKey(v))
            fusedList.addAll(b.get(v));
        }

        if (fusedList.size() > 0) {
          fusedMap.put(aVertex, fusedList);
          globalFusedSet.addAll(fusedList);
        }
      }
    }

    return new TraversalResult(fusedMap, null, globalFusedSet);
  }

  /**
   * Intersects the values in the list with those in the TraversalResult.
   * If the resulting value is an empty list, then remove the key from the map.
   * The resulting map will never have emtpy list values.
   *
   * @param trA TraversalResult to intersect values on.
   * @param b Values to intersect TraversalResult values with.
   */
  public static void intersect(
      TraversalResult trA,
      List<TorcVertex> b) {
    intersect(trA, new HashSet<>(b));
  }

  /**
   * Intersects the values in the set with those in the TraversalResult.
   * If the resulting value is an empty list, then remove the key from the map.
   * The resulting map will never have emtpy list values.
   *
   * @param trA TraversalResult to intersect values on.
   * @param b Values to intersect TraversalResult values with.
   */
  public static void intersect(
      TraversalResult trA,
      Set<TorcVertex> b) {
    if (trA.pMap == null) {
      Map<TorcVertex, List<TorcVertex>> newMap = new HashMap<>(trA.vMap.size());
      for (TorcVertex v : trA.vMap.keySet()) {
        List<TorcVertex> vList = trA.vMap.get(v);

        vList.retainAll(b);
        
        if (vList.size() > 0)
          newMap.put(v, vList);
      }

      trA.vMap = newMap;
    } else {
      Map<TorcVertex, List<TorcVertex>> newVMap = new HashMap<>(trA.vMap.size());
      Map<TorcVertex, List<Map<Object, Object>>> newPMap = new HashMap<>(trA.pMap.size());

      for (TorcVertex v : trA.vMap.keySet()) {
        List<TorcVertex> vList = trA.vMap.get(v);
        List<Map<Object, Object>> pList = trA.pMap.get(v);
        List<TorcVertex> newVList = new ArrayList<>(vList.size());
        List<Map<Object, Object>> newPList = new ArrayList<>(pList.size());

        for (int i = 0; i < vList.size(); i++) {
          if (b.contains(vList.get(i))) {
            newVList.add(vList.get(i));
            newPList.add(pList.get(i));
          }
        }

        if (newVList.size() > 0) {
          newVMap.put(v, newVList);
          newPMap.put(v, newPList);
        }
      }

      trA.vMap = newVMap;
      trA.pMap = newPMap;
    }

    trA.vSet.retainAll(b);
  }

  /**
   * Subtract the set from the TraversalResult.
   * If the resulting value is an empty list, then remove the key from the map.
   * The resulting map will never have emtpy list values.
   *
   * @param trA TraveresalResult to subtract values from.
   * @param b Values to subtract out of TraversalResult.
   */
  public static void subtract(
      TraversalResult trA,
      Set<TorcVertex> b) {
    if (trA.pMap == null) {
      Map<TorcVertex, List<TorcVertex>> newMap = new HashMap<>(trA.vMap.size());
      for (TorcVertex v : trA.vMap.keySet()) {
        List<TorcVertex> vList = trA.vMap.get(v);

        vList.removeAll(b);

        if (vList.size() > 0)
          newMap.put(v, vList);
      }

      trA.vMap = newMap;
    } else {
      Map<TorcVertex, List<TorcVertex>> newVMap = new HashMap<>(trA.vMap.size());
      Map<TorcVertex, List<Map<Object, Object>>> newPMap = new HashMap<>(trA.pMap.size());

      for (TorcVertex v : trA.vMap.keySet()) {
        List<TorcVertex> vList = trA.vMap.get(v);
        List<Map<Object, Object>> pList = trA.pMap.get(v);
        List<TorcVertex> newVList = new ArrayList<>(vList.size());
        List<Map<Object, Object>> newPList = new ArrayList<>(pList.size());

        for (int i = 0; i < vList.size(); i++) {
          if (!b.contains(vList.get(i))) {
            newVList.add(vList.get(i));
            newPList.add(pList.get(i));
          }
        }

        if (newVList.size() > 0) {
          newVMap.put(v, newVList);
          newPMap.put(v, newPList);
        }
      }

      trA.vMap = newVMap;
      trA.pMap = newPMap;
    }

    trA.vSet.removeAll(b);
  }

  public static List<TorcVertex> keylist(
      TraversalResult trA) {
    Map<TorcVertex, List<TorcVertex>> a = trA.vMap;
    List<TorcVertex> keylist = new ArrayList<TorcVertex>(a.size());
    keylist.addAll(a.keySet());
    return keylist;
  }
}
