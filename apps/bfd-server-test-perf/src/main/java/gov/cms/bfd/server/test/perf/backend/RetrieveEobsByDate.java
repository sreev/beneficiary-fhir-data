package gov.cms.bfd.server.test.perf.backend;

import gov.cms.bfd.server.test.perf.utils.BenefitIdManager;
import gov.cms.bfd.server.test.perf.utils.CsvBenefitIdManager;
import gov.cms.bfd.server.war.stu3.providers.TransformerUtils;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit;

/**
 * This JMeter sampler will run query for a FHIR {@link ExplanationOfBenefit} using the specified
 * benefit id and filter the results by date.
 */
public final class RetrieveEobsByDate extends CustomSamplerClient {
  private BenefitIdManager bim;

  /**
   * @see
   *     org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient#setupTest(org.apache.jmeter.protocol.java.sampler.JavaSamplerContext)
   */
  @Override
  public void setupTest(JavaSamplerContext context) {
    super.setupTest(context);
    bim = new CsvBenefitIdManager();
  }

  /** Test Implementation */
  @Override
  protected void executeTest() {
    // Removed the Rif parser to speed things up
    //		RifRecordEvent<?> rifRecordEvent = this.parser.next();
    //		if (rifRecordEvent != null)
    //		{
    //		}

    // TODO - query EOBs for a patient filtering by date
    client
        .search()
        .forResource(ExplanationOfBenefit.class)
        .where(ExplanationOfBenefit.PATIENT.hasId(TransformerUtils.buildPatientId(bim.nextId())))
        .returnBundle(Bundle.class)
        .execute();
    // }
  }
}
