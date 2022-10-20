/*rule = AddCatsTaglessInstances*/
/**
 * Generated by Scrooge
 *   version: 22.7.0
 *   rev: 2bb5fe76210bd2837988f9703fc9ca47d51bbe26
 *   built at: 20220728-182510
 */
package example.thrift

import com.twitter.io.Buf
import com.twitter.scrooge.{
  InvalidFieldsException,
  LazyTProtocol,
  StructBuilder,
  StructBuilderFactory,
  TFieldBlob,
  ThriftStruct,
  ThriftStructCodec3,
  ThriftStructField,
  ThriftStructFieldInfo,
  ThriftStructMetaData,
  ValidatingThriftStruct,
  ValidatingThriftStructCodec3
}
import org.apache.thrift.protocol._
import org.apache.thrift.transport.TMemoryBuffer
import scala.collection.immutable.{Map => immutable$Map}
import scala.collection.mutable.Builder
import scala.reflect.{ClassTag, classTag}


object SimpleResponse extends ValidatingThriftStructCodec3[SimpleResponse] with StructBuilderFactory[SimpleResponse] {
  val Struct: TStruct = new TStruct("SimpleResponse")
  val IdField: TField = new TField("id", TType.STRING, 1)
  val IdFieldManifest: Manifest[String] = manifest[String]

  /**
   * Field information in declaration order.
   */
  lazy val fieldInfos: scala.List[ThriftStructFieldInfo] = scala.List[ThriftStructFieldInfo](
    new ThriftStructFieldInfo(
      IdField,
      false,
      true,
      IdFieldManifest,
      _root_.scala.None,
      _root_.scala.None,
      immutable$Map.empty[String, String],
      immutable$Map.empty[String, String],
      None,
      _root_.scala.Option("empty")
    )
  )


  val structAnnotations: immutable$Map[String, String] =
    immutable$Map.empty[String, String]

  private val fieldTypes: IndexedSeq[ClassTag[_]] = IndexedSeq[ClassTag[_]](
    classTag[String].asInstanceOf[ClassTag[_]]
  )

  private[this] val structFields: Seq[ThriftStructField[SimpleResponse]] = Seq[ThriftStructField[SimpleResponse]](
    new ThriftStructField[SimpleResponse](
      IdField,
      _root_.scala.Some(IdFieldManifest),
      classOf[SimpleResponse]) {
        def getValue[R](struct: SimpleResponse): R = struct.id.asInstanceOf[R]
    }
  )

  override lazy val metaData: ThriftStructMetaData[SimpleResponse] =
    ThriftStructMetaData(this, structFields, fieldInfos, Nil, structAnnotations)

  /**
   * Checks that all required fields are non-null.
   */
  def validate(_item: SimpleResponse): Unit = {
    if (_item.id eq null) throw new TProtocolException("Required field id cannot be null")
  }

  /**
   * Checks that the struct is a valid as a new instance. If there are any missing required or
   * construction required fields, return a non-empty list.
   */
  def validateNewInstance(item: SimpleResponse): scala.Seq[com.twitter.scrooge.validation.Issue] = {
    val buf = scala.collection.mutable.ListBuffer.empty[com.twitter.scrooge.validation.Issue]

    if (item.id eq null)
      buf += com.twitter.scrooge.validation.MissingRequiredField(fieldInfos.apply(0))
    buf ++= validateField(item.id)
    buf.toList
  }

  /**
   * Validate that all validation annotations on the struct meet the criteria defined in the
   * corresponding [[com.twitter.scrooge.validation.ThriftConstraintValidator]].
   */
  def validateInstanceValue(item: SimpleResponse): Set[com.twitter.scrooge.thrift_validation.ThriftValidationViolation] = {
    val violations = scala.collection.mutable.Set.empty[com.twitter.scrooge.thrift_validation.ThriftValidationViolation]
    violations ++= validateFieldValue("id", item.id, fieldInfos.apply(0).fieldAnnotations, scala.None)
    violations.toSet
  }

  def withoutPassthroughFields(original: SimpleResponse): SimpleResponse =
    new Immutable(
      id = original.id
    )

  lazy val unsafeEmpty: SimpleResponse = {
    val id: String = "empty"

    new Immutable(
      id,
      _root_.com.twitter.scrooge.internal.TProtocols.NoPassthroughFields
    )
  }

  def newBuilder(): StructBuilder[SimpleResponse] = new SimpleResponseStructBuilder(_root_.scala.None, fieldTypes)

  override def encode(_item: SimpleResponse, _oproto: TProtocol): Unit = {
    _item.write(_oproto)
  }


  override def decode(_iprot: TProtocol): SimpleResponse = {
    if (_iprot.isInstanceOf[LazyTProtocol]) {
      decodeInternal(_iprot, true)
    } else {
      decodeInternal(_iprot, false)
    }
  }

  private[thrift] def eagerDecode(_iprot: TProtocol): SimpleResponse = {
    decodeInternal(_iprot, false)
  }

  private[this] def decodeInternal(_iprot: TProtocol, lazily: Boolean): SimpleResponse = {
    var idOffset: Int = -1
    var id: String = null
    var _got_id = false

    var _passthroughFields: Builder[(Short, TFieldBlob), immutable$Map[Short, TFieldBlob]] = null
    var _done = false
    val _start_offset = if (lazily) _iprot.asInstanceOf[LazyTProtocol].offset else -1

    _iprot.readStructBegin()
    do {
      val _field = _iprot.readFieldBegin()
      val _fieldType = _field.`type`
      if (_fieldType == TType.STOP) {
        _done = true
      } else {
        _field.id match {
          case 1 =>
            _root_.com.twitter.scrooge.internal.TProtocols.validateFieldType(TType.STRING, _fieldType, "id")
            if (lazily)
              idOffset = _iprot.asInstanceOf[LazyTProtocol].offsetSkipString()
            else
              id = _iprot.readString()
            _got_id = true
          case _ =>
            _passthroughFields = _root_.com.twitter.scrooge.internal.TProtocols.readPassthroughField(_iprot, _field, _passthroughFields)
        }
        _iprot.readFieldEnd()
      }
    } while (!_done)
    _iprot.readStructEnd()

    if (!_got_id) _root_.com.twitter.scrooge.internal.TProtocols.throwMissingRequiredField("SimpleResponse", "id")

    val _passthroughFieldsResult =
      if (_passthroughFields eq null) _root_.com.twitter.scrooge.internal.TProtocols.NoPassthroughFields
      else _passthroughFields.result()
    if (lazily) {
      val _lazyProt = _iprot.asInstanceOf[LazyTProtocol]
      new LazyImmutable(
        _lazyProt,
        _lazyProt.buffer,
        _start_offset,
        _lazyProt.offset,
        idOffset,
        _passthroughFieldsResult
      )
    } else {
      new Immutable(
        id,
        _passthroughFieldsResult
      )
    }
  }

  def apply(
    id: String
  ): SimpleResponse =
    new Immutable(
      id
    )

  def unapply(_item: SimpleResponse): _root_.scala.Option[String] = _root_.scala.Some(_item.id)



  object Immutable extends ThriftStructCodec3[SimpleResponse] {
    override def encode(_item: SimpleResponse, _oproto: TProtocol): Unit = { _item.write(_oproto) }
    override def decode(_iprot: TProtocol): SimpleResponse = SimpleResponse.decode(_iprot)
    override lazy val metaData: ThriftStructMetaData[SimpleResponse] = SimpleResponse.metaData
  }

  /**
   * The default read-only implementation of SimpleResponse.  You typically should not need to
   * directly reference this class; instead, use the SimpleResponse.apply method to construct
   * new instances.
   */
  class Immutable(
      val id: String,
      override val _passthroughFields: immutable$Map[Short, TFieldBlob])
    extends SimpleResponse {
    def this(
      id: String
    ) = this(
      id,
      immutable$Map.empty[Short, TFieldBlob]
    )
  }

  /**
   * This is another Immutable, this however keeps strings as lazy values that are lazily decoded from the backing
   * array byte on read.
   */
  private[this] class LazyImmutable(
      _proto: LazyTProtocol,
      _buf: Array[Byte],
      _start_offset: Int,
      _end_offset: Int,
      idOffset: Int,
      override val _passthroughFields: immutable$Map[Short, TFieldBlob])
    extends SimpleResponse {

    override def write(_oprot: TProtocol): Unit = {
      if (_oprot.isInstanceOf[LazyTProtocol]) {
        _oprot.asInstanceOf[LazyTProtocol].writeRaw(_buf, _start_offset, _end_offset - _start_offset)
      } else {
        super.write(_oprot)
      }
    }

    lazy val id: String =
      if (idOffset == -1)
        null
      else {
        _proto.decodeString(_buf, idOffset)
      }

    /**
     * Override the super hash code to make it a lazy val rather than def.
     *
     * Calculating the hash code can be expensive, caching it where possible
     * can provide significant performance wins. (Key in a hash map for instance)
     * Usually not safe since the normal constructor will accept a mutable map or
     * set as an arg
     * Here however we control how the class is generated from serialized data.
     * With the class private and the contract that we throw away our mutable references
     * having the hash code lazy here is safe.
     */
    override lazy val hashCode: Int = super.hashCode
  }

}

/**
 * Prefer the companion object's [[example.thrift.SimpleResponse.apply]]
 * for construction if you don't need to specify passthrough fields.
 */
trait SimpleResponse
  extends ThriftStruct
  with _root_.scala.Product1[String]
  with ValidatingThriftStruct[SimpleResponse]
  with java.io.Serializable
{
  import SimpleResponse._

  def id: String

  def _passthroughFields: immutable$Map[Short, TFieldBlob] = immutable$Map.empty

  def _1: String = id


  /**
   * Gets a field value encoded as a binary blob using TCompactProtocol.  If the specified field
   * is present in the passthrough map, that value is returned.  Otherwise, if the specified field
   * is known and not optional and set to None, then the field is serialized and returned.
   */
  def getFieldBlob(_fieldId: Short): _root_.scala.Option[TFieldBlob] = {
    val passedthroughValue = _passthroughFields.get(_fieldId)
    if (passedthroughValue.isDefined) {
      passedthroughValue
    } else {
      val _buff = new TMemoryBuffer(32)
      val _oprot = new TCompactProtocol(_buff)

      val _fieldOpt: _root_.scala.Option[TField] = _fieldId match {
        case 1 =>
          if (id ne null) {
            _oprot.writeString(id)
            _root_.scala.Some(SimpleResponse.IdField)
          } else {
            _root_.scala.None
          }
        case _ => _root_.scala.None
      }
      if (_fieldOpt.isDefined) {
        _root_.scala.Some(TFieldBlob(_fieldOpt.get, Buf.ByteArray.Owned(_buff.getArray)))
      } else {
        _root_.scala.None
      }
    }
  }


  /**
   * Collects TCompactProtocol-encoded field values according to `getFieldBlob` into a map.
   */
  def getFieldBlobs(ids: TraversableOnce[Short]): immutable$Map[Short, TFieldBlob] =
    (ids.flatMap { id => getFieldBlob(id).map { fieldBlob => (id, fieldBlob) } }).toMap

  /**
   * Sets a field using a TCompactProtocol-encoded binary blob.  If the field is a known
   * field, the blob is decoded and the field is set to the decoded value.  If the field
   * is unknown and passthrough fields are enabled, then the blob will be stored in
   * _passthroughFields.
   */
  def setField(_blob: TFieldBlob): SimpleResponse = {
    var id: String = this.id
    var _passthroughFields = this._passthroughFields
    val _iprot = _blob.read 
    _blob.id match {
      case 1 =>
        id = _iprot.readString()
      case _ => _passthroughFields += _root_.scala.Tuple2(_blob.id, _blob)
    }
    new Immutable(
      id,
      _passthroughFields
    )
  }

  /**
   * If the specified field is optional, it is set to None.  Otherwise, if the field is
   * known, it is reverted to its default value; if the field is unknown, it is removed
   * from the passthroughFields map, if present.
   */
  def unsetField(_fieldId: Short): SimpleResponse = {
    var id: String = this.id

    _fieldId match {
      case 1 =>
        id = null
      case _ =>
    }
    new Immutable(
      id,
      _passthroughFields - _fieldId
    )
  }

  /**
   * If the specified fields are optional, they are set to None.  Otherwise, if the fields are
   * known, they are reverted to their default values; if the fields are unknown, they are removed
   * from the passthroughFields map, if present.
   */
  def unsetFields(_fieldIds: Set[Short]): SimpleResponse = {
    new Immutable(
      if (_fieldIds(1)) null else this.id,
      _passthroughFields -- _fieldIds
    )
  }

  /**
   * If the specified field is optional, it is set to None.  Otherwise, if the field is
   * known, it is reverted to its default value; if the field is unknown, it is removed
   * from the passthroughFields map, if present.
   */
  def unsetId: SimpleResponse = unsetField(1)


  override def write(_oprot: TProtocol): Unit = {
    SimpleResponse.validate(this)
    _oprot.writeStructBegin(Struct)
    if (id ne null) {
      _oprot.writeFieldBegin(IdField)
      _oprot.writeString(id)
      _oprot.writeFieldEnd()
    }
    _root_.com.twitter.scrooge.internal.TProtocols.finishWritingStruct(_oprot, _passthroughFields)
  }

  def copy(
    id: String = this.id,
    _passthroughFields: immutable$Map[Short, TFieldBlob] = this._passthroughFields
  ): SimpleResponse =
    new Immutable(
      id,
      _passthroughFields
    )

  override def canEqual(other: Any): Boolean = other.isInstanceOf[SimpleResponse]

  private[this] def _equals(other: SimpleResponse): Boolean =
      this.productArity == other.productArity &&
      this.productIterator.sameElements(other.productIterator) &&
      this._passthroughFields == other._passthroughFields

  override def equals(other: Any): Boolean =
    canEqual(other) && _equals(other.asInstanceOf[SimpleResponse])

  override def hashCode: Int = {
    _root_.scala.runtime.ScalaRunTime._hashCode(this)
  }

  override def toString: String = _root_.scala.runtime.ScalaRunTime._toString(this)

  override def productPrefix: String = "SimpleResponse"

  def _codec: ValidatingThriftStructCodec3[SimpleResponse] = SimpleResponse

  def newBuilder(): StructBuilder[SimpleResponse] = new SimpleResponseStructBuilder(_root_.scala.Some(this), fieldTypes)
}

private[thrift] class SimpleResponseStructBuilder(instance: _root_.scala.Option[SimpleResponse], fieldTypes: IndexedSeq[ClassTag[_]])
    extends StructBuilder[SimpleResponse](fieldTypes) {

  def build(): SimpleResponse = {
    val _fieldArray = fieldArray // shadow variable
    if (instance.isDefined) {
      val instanceValue = instance.get
      SimpleResponse(
        if (_fieldArray(0) == null) instanceValue.id else _fieldArray(0).asInstanceOf[String]
      )
    } else {
      if (genericArrayOps(_fieldArray).contains(null)) throw new InvalidFieldsException(structBuildError("SimpleResponse"))
      SimpleResponse(
        _fieldArray(0).asInstanceOf[String]
      )
    }
  }
}

