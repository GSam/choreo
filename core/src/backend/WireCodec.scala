package choreo
package backend

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

/** Encodes and decodes values for transmission over the wire. */
trait WireCodec:
  def encode(value: Any): Array[Byte]
  def decode(bytes: Array[Byte]): Any

object WireCodec:

  /** Default codec using Java serialization. */
  object javaSerialization extends WireCodec:
    def encode(value: Any): Array[Byte] =
      val baos = new ByteArrayOutputStream()
      val oos  = new ObjectOutputStream(baos)
      oos.writeObject(value)
      oos.flush()
      baos.toByteArray

    def decode(bytes: Array[Byte]): Any =
      val ois = new ObjectInputStream(new ByteArrayInputStream(bytes))
      ois.readObject()
