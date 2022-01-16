package io.circe

import cats.kernel.Eq
import cats.kernel.instances.all.*
import cats.syntax.eq.*
import cats.data.Validated
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json}
import io.circe.CursorOp.DownField
import io.circe.testing.CodecTests
import io.circe.tests.CirceMunitSuite
import io.circe.derivation.*
import io.circe.syntax.*
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

object ConfiguredDerivesSuite:
  // "derives ConfiguredCodec" is not here so we can change the configuration for the derivation in each test
  enum ConfigExampleBase:
    case ConfigExampleFoo(thisIsAField: String, a: Int = 0, b: Double)
    case ConfigExampleBar
  object ConfigExampleBase:
    given Eq[ConfigExampleBase] = Eq.fromUniversalEquals
    given Eq[ConfigExampleBase.ConfigExampleFoo] = Eq.fromUniversalEquals

    given genConfigExampleFoo: Gen[ConfigExampleBase.ConfigExampleFoo] = for {
      thisIsAField <- Arbitrary.arbitrary[String]
      a <- Arbitrary.arbitrary[Int]
      b <- Arbitrary.arbitrary[Double]
    } yield ConfigExampleBase.ConfigExampleFoo(thisIsAField, a, b)
    given Arbitrary[ConfigExampleBase.ConfigExampleFoo] = Arbitrary(genConfigExampleFoo)
    given Arbitrary[ConfigExampleBase] = Arbitrary(
      Gen.oneOf(
        genConfigExampleFoo,
        Gen.const(ConfigExampleBase.ConfigExampleBar),
      )
    )

class ConfiguredDerivesSuite extends CirceMunitSuite:
  import ConfiguredDerivesSuite.{*, given}

  {
    given Configuration = Configuration.default
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    checkAll("Codec[ConfigExampleBase] (default configuration)", CodecTests[ConfigExampleBase].codec)
  }

  test("Fail when the json to be decoded is not a Json object") {
    given Configuration = Configuration.default
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    given Codec[ConfigExampleBase.ConfigExampleFoo] = ConfiguredCodec.derived

    val json = Json.fromString("a string")
    def failure(name: String) = DecodingFailure(s"$name: expected a json object.", List())
    assert(Decoder[ConfigExampleBase].decodeJson(json) === Left(failure("ConfigExampleBase"))) // sum type
    assert(Decoder[ConfigExampleBase.ConfigExampleFoo].decodeJson(json) === Left(failure("ConfigExampleFoo"))) // product type
  }

  test("Fail to decode if case name does not exist") {
    given Configuration = Configuration.default
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    
    val json = Json.obj("invalid-name" -> Json.obj(
      "thisIsAField" -> "not used".asJson,
      "a" -> 0.asJson,
      "b" -> 2.5.asJson,
    ))
    val failure = DecodingFailure("type ConfigExampleBase has no class/object/case named 'invalid-name'.", List(DownField("invalid-name")))
    assert(Decoder[ConfigExampleBase].decodeJson(json) === Left(failure))
    assert(Decoder[ConfigExampleBase].decodeAccumulating(json.hcursor) === Validated.invalidNel(failure))
  }

  test("Fail to decode if case name does not exist when constructor names are being transformed") {
    given Configuration = Configuration.default.withSnakeCaseConstructorNames
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    
    val json = Json.obj("ConfigExampleFoo" -> Json.obj(
      "thisIsAField" -> "not used".asJson,
      "a" -> 0.asJson,
      "b" -> 2.5.asJson,
    ))
    // Can we improve the message so it is not so misleading?
    val failure = DecodingFailure("type ConfigExampleBase has no class/object/case named 'ConfigExampleFoo'.", List(DownField("ConfigExampleFoo")))
    assert(Decoder[ConfigExampleBase].decodeJson(json) === Left(failure))
    assert(Decoder[ConfigExampleBase].decodeAccumulating(json.hcursor) === Validated.invalidNel(failure))
  }

  property("Configuration#transformMemberNames should support member name transformation using snake_case") {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withSnakeCaseMemberNames
      given Codec[ConfigExampleBase.ConfigExampleFoo] = ConfiguredCodec.derived
      
      val json = Json.obj(
        "this_is_a_field" -> foo.thisIsAField.asJson,
        "a" -> foo.a.asJson,
        "b" -> foo.b.asJson,
      )
      assert(summon[Encoder[ConfigExampleBase.ConfigExampleFoo]].apply(foo) === json)
      assert(summon[Decoder[ConfigExampleBase.ConfigExampleFoo]].decodeJson(json) === Right(foo))
    }
  }

  property("Configuration#transformMemberNames should support member name transformation using SCREAMING_SNAKE_CASE") {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withScreamingSnakeCaseMemberNames
      given Codec[ConfigExampleBase.ConfigExampleFoo] = ConfiguredCodec.derived
      
      val json = Json.obj(
        "THIS_IS_A_FIELD" -> foo.thisIsAField.asJson,
        "A" -> foo.a.asJson,
        "B" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase.ConfigExampleFoo].apply(foo) === json)
      assert(Decoder[ConfigExampleBase.ConfigExampleFoo].decodeJson(json) === Right(foo))
    }
  }
  
  property("Configuration#transformMemberNames should support member name transformation using kebab-case") {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withKebabCaseMemberNames
      given Codec[ConfigExampleBase.ConfigExampleFoo] = ConfiguredCodec.derived
      val json = Json.obj(
        "this-is-a-field" -> foo.thisIsAField.asJson,
        "a" -> foo.a.asJson,
        "b" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase.ConfigExampleFoo].apply(foo) === json)
      assert(Decoder[ConfigExampleBase.ConfigExampleFoo].decodeJson(json) === Right(foo))
    }
  }
  
  property("Configuration#useDefaults should support using default values during decoding") {
    forAll { (f: String, b: Double) =>
      given Configuration = Configuration.default.withDefaults
      given Codec[ConfigExampleBase.ConfigExampleFoo] = ConfiguredCodec.derived
      
      val foo: ConfigExampleBase.ConfigExampleFoo = ConfigExampleBase.ConfigExampleFoo(f, 0, b)
      val json = Json.obj(
        "thisIsAField" -> f.asJson,
        "b" -> b.asJson,
      )
      val expected = Json.obj(
        "thisIsAField" -> f.asJson,
        "a" -> 0.asJson,
        "b" -> b.asJson,
      )
      assert(Encoder[ConfigExampleBase.ConfigExampleFoo].apply(foo) === expected)
      assert(Decoder[ConfigExampleBase.ConfigExampleFoo].decodeJson(json) === Right(foo))
    }
  }
  
  {
    given Configuration = Configuration.default.withDefaults

    case class FooWithDefault(a: Option[Int] = Some(0), b: String = "b") derives ConfiguredCodec
    object FooWithDefault:
      given Eq[FooWithDefault] = Eq.fromUniversalEquals

    case class FooNoDefault(a: Option[Int], b: String = "b") derives ConfiguredCodec
    object FooNoDefault:
      given Eq[FooNoDefault] = Eq.fromUniversalEquals

    test("Option[T] without default should be None if null decoded") {
      val json = Json.obj("a" -> Json.Null)
      assert(Decoder[FooNoDefault].decodeJson(json) === Right(FooNoDefault(None, "b")))
    }
    
    test("Option[T] without default should be None if missing key decoded") {
      val json = Json.obj()
      assert(Decoder[FooNoDefault].decodeJson(json) === Right(FooNoDefault(None, "b")))
    }
    
    test("Option[T] with default should be None if null decoded") {
      val json = Json.obj("a" -> Json.Null)
      assert(Decoder[FooWithDefault].decodeJson(json) === Right(FooWithDefault(None, "b")))
    }

    test("Option[T] with default should be default value if missing key decoded") {
      val json = Json.obj()
      assert(Decoder[FooWithDefault].decodeJson(json) === Right(FooWithDefault(Some(0), "b")))
      assert(Decoder[FooWithDefault].decodeAccumulating(json.hcursor) === Validated.valid(FooWithDefault(Some(0), "b")))
    }

    test("Value with default should be default value if value is null") {
      val json = Json.obj("b" -> Json.Null)
      assert(Decoder[FooWithDefault].decodeJson(json) === Right(FooWithDefault(Some(0), "b")))
      assert(Decoder[FooWithDefault].decodeAccumulating(json.hcursor) === Validated.valid(FooWithDefault(Some(0), "b")))
    }

    test("Option[T] with default should fail to decode if type in json is not correct") {
      val json = Json.obj("a" -> "NotAnInt".asJson)
      assert(Decoder[FooWithDefault].decodeJson(json) === Left(DecodingFailure("Int", List(DownField("a")))))
      assert(
        Decoder[FooWithDefault].decodeAccumulating(json.hcursor)
          === Validated.invalidNel(DecodingFailure("Int", List(DownField("a"))))
      )
    }

    test("Field with default should fail to decode it type in json is not correct") {
      val json = Json.obj("b" -> 25.asJson)      
      val reason = DecodingFailure.Reason.WrongTypeExpectation("string", 25.asJson)
      val failure = DecodingFailure(reason, List(DownField("b")))
      assert(Decoder[FooWithDefault].decodeJson(json) === Left(failure))
      assert(Decoder[FooWithDefault].decodeAccumulating(json.hcursor) === Validated.invalidNel(failure))
    }
  }

  test("Decoding when Configuration#discriminator is set should fail if the discriminator field does not exist or its null") {
    given Configuration = Configuration.default.withDiscriminator("type")
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    
    val failure = DecodingFailure("ConfigExampleBase: could not find discriminator field 'type' or its null.", List(DownField("type")))
    
    val json1 = Json.obj(
      "_notType" -> "ConfigExampleFoo".asJson,
      "thisIsAField" -> "not used".asJson,
      "a" -> 0.asJson,
      "b" -> 2.5.asJson,
    )
    assert(Decoder[ConfigExampleBase].decodeJson(json1) === Left(failure))
    assert(Decoder[ConfigExampleBase].decodeAccumulating(json1.hcursor) === Validated.invalidNel(failure))

    val json2 = Json.obj(
      "_notType" -> Json.Null,
      "thisIsAField" -> "not used".asJson,
      "a" -> 0.asJson,
      "b" -> 2.5.asJson,
    )
    assert(Decoder[ConfigExampleBase].decodeJson(json2) === Left(failure))
    assert(Decoder[ConfigExampleBase].decodeAccumulating(json2.hcursor) === Validated.invalidNel(failure))
  }

  property("Configuration#discriminator should support a field indicating constructor") {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withDiscriminator("type")
      given Codec[ConfigExampleBase] = ConfiguredCodec.derived
      
      val json = Json.obj(
        "type" -> "ConfigExampleFoo".asJson,
        "thisIsAField" -> foo.thisIsAField.asJson,
        "a" -> foo.a.asJson,
        "b" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase].apply(foo) === json)
      assert(Decoder[ConfigExampleBase].decodeJson(json) === Right(foo))
    }
  }

  property("Configuration#transformConstructorNames should support constructor name transformation with snake_case") {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withDiscriminator("type").withSnakeCaseConstructorNames
      given Codec[ConfigExampleBase] = ConfiguredCodec.derived
      
      val json = Json.obj(
        "type" -> "config_example_foo".asJson,
        "thisIsAField" -> foo.thisIsAField.asJson,
        "a" -> foo.a.asJson,
        "b" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase].apply(foo) === json)
      assert(Decoder[ConfigExampleBase].decodeJson(json) === Right(foo))
    }
  }

  property(
    "Configuration#transformConstructorNames should support constructor name transformation with SCREAMING_SNAKE_CASE"
  ) {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withDiscriminator("type").withScreamingSnakeCaseConstructorNames
      given Codec[ConfigExampleBase] = ConfiguredCodec.derived
      
      val json = Json.obj(
        "type" -> "CONFIG_EXAMPLE_FOO".asJson,
        "thisIsAField" -> foo.thisIsAField.asJson,
        "a" -> foo.a.asJson,
        "b" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase].apply(foo) === json)
      assert(Decoder[ConfigExampleBase].decodeJson(json) === Right(foo))
    }
  }

  property("Configuration#transformConstructorNames should support constructor name transformation with kebab-case") {
    forAll { (foo: ConfigExampleBase.ConfigExampleFoo) =>
      given Configuration = Configuration.default.withDiscriminator("type").withKebabCaseConstructorNames
      given Codec[ConfigExampleBase] = ConfiguredCodec.derived
      
      val json = Json.obj(
        "type" -> "config-example-foo".asJson,
        "thisIsAField" -> foo.thisIsAField.asJson,
        "a" -> foo.a.asJson,
        "b" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase].apply(foo) === json)
      assert(Decoder[ConfigExampleBase].decodeJson(json) === Right(foo))
    }
  }

  property("Configuration options should work together") {
    forAll { (f: String, b: Double) =>
      given Configuration = Configuration.default
        .withSnakeCaseMemberNames
        .withDefaults
        .withDiscriminator("type")
        .withKebabCaseConstructorNames
      given Codec[ConfigExampleBase] = ConfiguredCodec.derived
      
      val foo: ConfigExampleBase.ConfigExampleFoo = ConfigExampleBase.ConfigExampleFoo(f, 0, b)
      val json = Json.obj(
        "type" -> "config-example-foo".asJson,
        "this_is_a_field" -> foo.thisIsAField.asJson,
        "b" -> foo.b.asJson,
      )
      val expected = Json.obj(
        "type" -> "config-example-foo".asJson,
        "this_is_a_field" -> foo.thisIsAField.asJson,
        "a" -> 0.asJson,
        "b" -> foo.b.asJson,
      )
      assert(Encoder[ConfigExampleBase].apply(foo) === expected)
      assert(Decoder[ConfigExampleBase].decodeJson(json) === Right(foo))
    }
  }

  test("Configuration#strictDecoding should fail for sum types when the json object has more than one field") {
    given Configuration = Configuration.default.withStrictDecoding
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    
    val json = Json.obj(
      "ConfigExampleFoo" -> Json.obj(
        "thisIsAField" -> "not used".asJson,
        "a" -> 0.asJson,
        "b" -> 2.5.asJson,
      ),
      "anotherField" -> "some value".asJson,
    )
    val failure = DecodingFailure(s"Strict decoding ConfigExampleBase - expected a single key json object with one of: ConfigExampleFoo, ConfigExampleBar.", List())
    assert(Decoder[ConfigExampleBase].decodeJson(json) === Left(failure))
    assert(Decoder[ConfigExampleBase].decodeAccumulating(json.hcursor) === Validated.invalidNel(failure))
  }

  test("Configuration#strictDecoding should fail for product types when the json object has more fields then expected") {
    given Configuration = Configuration.default.withStrictDecoding
    given Codec[ConfigExampleBase] = ConfiguredCodec.derived
    
    val json = Json.obj(
      "ConfigExampleFoo" -> Json.obj(
        "thisIsAField" -> "not used".asJson,
        "a" -> 0.asJson,
        "b" -> 2.5.asJson,
        "anotherField" -> "some value".asJson,
      ),
    )
    def failure(submessage: String) = DecodingFailure(
      s"Strict decoding ConfigExampleFoo - $submessage; valid fields: thisIsAField, a, b.",
      List(DownField("ConfigExampleFoo"))
    )
    assert(Decoder[ConfigExampleBase].decodeJson(json) === Left(failure("unexpected fields: anotherField")))
    assert(Decoder[ConfigExampleBase].decodeAccumulating(json.hcursor) === Validated.invalidNel(failure("unexpected field: anotherField")))
  }