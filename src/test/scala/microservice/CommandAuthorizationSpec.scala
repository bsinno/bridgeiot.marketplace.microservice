package microservice

import io.funcqrs.AggregateId
import microservice.entity.Id
import org.scalatest.{FlatSpec, Matchers}

class CommandAuthorizationSpec extends FlatSpec with Matchers {
  case class TestCommand(id: AggregateId, meta: Meta) extends Command

  def cmd(requesterId: Option[String] = None, requesterOrgId: Option[Id] = None) = 
    TestCommand(new AggregateId {val value = "org-abc"}, Meta(requesterId, requesterOrgId))

  "Command with no requesterId" should "be anonymous" in {
    cmd(None).isAnonymous shouldBe true
  }

  "Command with empty requesterId" should "be anonymous" in {
    cmd(Some("")).isAnonymous shouldBe true
  }

  "Command with non-empty requesterId" should "not be anonymous" in {
    cmd(Some("xxx")).isAnonymous shouldBe false
  }

  "Command with empty requesterId and no requesterOrgId" should "have no Organization" in {
    cmd(Some(""), None).hasNoOrganization shouldBe true
  }

  "Command with no requesterId and empty requesterOrgId" should "have no Organization" in {
    cmd(None, Some("")).hasNoOrganization shouldBe true
  }

  "Command with empty requesterId and empty requesterOrgId" should "have no Organization" in {
    cmd(Some(""), Some("")).hasNoOrganization shouldBe true
  }

  "Command with non-empty requesterId and no requesterOrgId" should "have no Organization" in {
    cmd(Some("xxx"), None).hasNoOrganization shouldBe true
  }

  "Command with non-empty requesterId and non-empty requesterOrgId" should "have Organization" in {
    cmd(Some("xxx"), Some("yyy")).hasNoOrganization shouldBe false
  }

  "Command with no requesterId and no requesterOrgId" should "have wrong Organization" in {
    cmd(None, None).hasWrongOrganization shouldBe true
  }

  "Command with empty requesterId and no requesterOrgId" should "have wrong Organization" in {
    cmd(Some(""), None).hasWrongOrganization shouldBe true
  }

  "Command with no requesterId and empty requesterOrgId" should "have wrong Organization" in {
    cmd(None, Some("")).hasWrongOrganization shouldBe true
  }

  "Command with empty requesterId and empty requesterOrgId" should "have wrong Organization" in {
    cmd(Some(""), Some("")).hasWrongOrganization shouldBe true
  }

  "Command with non-empty requesterId and no requesterOrgId" should "have wrong Organization" in {
    cmd(Some("xxx"), None).hasWrongOrganization shouldBe true
  }

  "Command with non-empty requesterId and wrong requesterOrgId" should "have wrong Organization" in {
    cmd(Some("xxx"), Some("yyy")).hasWrongOrganization shouldBe true
  }

  "Command with non-empty requesterId and correct requesterOrgId" should "have authorized Organization" in {
    cmd(Some("xxx"), Some("org")).hasWrongOrganization shouldBe false
  }

}
