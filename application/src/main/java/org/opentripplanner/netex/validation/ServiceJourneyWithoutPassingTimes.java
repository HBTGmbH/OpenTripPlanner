package org.opentripplanner.netex.validation;

import org.opentripplanner.graph_builder.issue.api.DataImportIssue;
import org.opentripplanner.graph_builder.issue.api.Issue;
import org.rutebanken.netex.model.ServiceJourney;

/**
 * Discard service journeys without a {@code passingTimes} element. The OTP model requires a time
 * at every stop of a trip. Profiles like the German VDV 462 do not exchange passing times; they
 * have to be derived before the validation runs.
 */
class ServiceJourneyWithoutPassingTimes extends AbstractHMapValidationRule<String, ServiceJourney> {

  @Override
  public Status validate(ServiceJourney sj) {
    return sj.getPassingTimes() == null ? Status.DISCARD : Status.OK;
  }

  @Override
  public DataImportIssue logMessage(String key, ServiceJourney sj) {
    return Issue.issue(
      "ServiceJourneyWithoutPassingTimes",
      "ServiceJourney %s has no passing times and is skipped.",
      sj.getId()
    );
  }
}
