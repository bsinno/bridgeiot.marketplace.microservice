package microservice

import org.scalatest.{FlatSpec, Matchers}

class AuthorizationSpec extends FlatSpec with Matchers {
  "With no requesterId" should "be anonymous" in {
     isAnonymous(None) shouldBe true
  }

  "With empty requesterId" should "be anonymous" in {
     isAnonymous(Some("")) shouldBe true
  }

  "With non-empty requesterId" should "not be anonymous" in {
     isAnonymous(Some("xxx")) shouldBe false
  }

  "With no requesterId and no requesterOrgId" should "have no Organization" in {
    hasNoOrganization(None, None) shouldBe true
  }

  "With empty requesterId and no requesterOrgId" should "have no Organization" in {
    hasNoOrganization(Some(""), None) shouldBe true
  }

  "With no requesterId and empty requesterOrgId" should "have no Organization" in {
    hasNoOrganization(None, Some("")) shouldBe true
  }

  "With empty requesterId and empty requesterOrgId" should "have no Organization" in {
    hasNoOrganization(Some(""), Some("")) shouldBe true
  }

  "With non-empty requesterId and no requesterOrgId" should "have no Organization" in {
    hasNoOrganization(Some("xxx"), None) shouldBe true
  }

  "With non-empty requesterId and non-empty requesterOrgId" should "have Organization" in {
    hasNoOrganization(Some("xxx"), Some("yyy")) shouldBe false
  }

  "With no requesterId and no requesterOrgId" should "have wrong Organization" in {
    hasWrongOrganization(None, None, "org-abc") shouldBe true
  }

  "With empty requesterId and no requesterOrgId" should "have wrong Organization" in {
    hasWrongOrganization(Some(""), None, "org-abc") shouldBe true
  }

  "With no requesterId and empty requesterOrgId" should "have wrong Organization" in {
    hasWrongOrganization(None, Some(""), "org-abc") shouldBe true
  }

  "With empty requesterId and empty requesterOrgId" should "have wrong Organization" in {
    hasWrongOrganization(Some(""), Some(""), "org-abc") shouldBe true
  }

  "With non-empty requesterId and no requesterOrgId" should "have wrong Organization" in {
    hasWrongOrganization(Some("xxx"), None, "org-abc") shouldBe true
  }

  "With non-empty requesterId and wrong requesterOrgId" should "have wrong Organization" in {
    hasWrongOrganization(Some("xxx"), Some("yyy"), "org-abc") shouldBe true
  }

  "With non-empty requesterId and correct requesterOrgId" should "have authorized Organization" in {
    hasWrongOrganization(Some("xxx"), Some("org"), "org-abc") shouldBe false
  }

}
