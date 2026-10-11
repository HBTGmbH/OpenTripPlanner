package org.opentripplanner.netex.mapping;

import com.google.common.collect.Multimap;
import jakarta.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.netex.config.NetexProfileSupport;
import org.opentripplanner.netex.index.api.NetexEntityIndexReadOnlyView;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.netex.mapping.support.NetexMainAndSubMode;
import org.opentripplanner.transit.model.framework.EntityById;
import org.opentripplanner.transit.model.network.BikeAccess;
import org.opentripplanner.transit.model.network.GroupOfRoutes;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.RouteBuilder;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.organization.Branding;
import org.opentripplanner.transit.model.organization.Operator;
import org.rutebanken.netex.model.AllVehicleModesOfTransportEnumeration;
import org.rutebanken.netex.model.BrandingRefStructure;
import org.rutebanken.netex.model.FlexibleLine_VersionStructure;
import org.rutebanken.netex.model.GroupOfLinesRefStructure;
import org.rutebanken.netex.model.Line_VersionStructure;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.Network;
import org.rutebanken.netex.model.OperatorRefStructure;
import org.rutebanken.netex.model.PresentationStructure;

/**
 * Maps NeTEx line to OTP Route.
 */
class RouteMapper {

  private final DataImportIssueStore issueStore;
  private final HexBinaryAdapter hexBinaryAdapter = new HexBinaryAdapter();
  private final TransportModeMapper transportModeMapper;

  private final FeedScopedIdFactory idFactory;
  private final EntityById<Agency> agenciesById;
  private final EntityById<Operator> operatorsById;
  private final EntityById<Branding> brandingsById;
  private final Multimap<FeedScopedId, GroupOfRoutes> groupsOfLinesByRouteId;
  private final EntityById<GroupOfRoutes> groupOfRoutesById;
  private final NetexEntityIndexReadOnlyView netexIndex;
  private final AuthorityToAgencyMapper authorityMapper;
  private final Set<String> ferryIdsNotAllowedForBicycle;
  private final NetexProfileSupport profileSupport;
  private final LineTransportModeResolver lineTransportModeResolver;

  RouteMapper(
    DataImportIssueStore issueStore,
    FeedScopedIdFactory idFactory,
    EntityById<Agency> agenciesById,
    EntityById<Operator> operatorsById,
    EntityById<Branding> brandingsById,
    Multimap<FeedScopedId, GroupOfRoutes> groupsOfLinesByRouteId,
    EntityById<GroupOfRoutes> groupOfRoutesById,
    NetexEntityIndexReadOnlyView netexIndex,
    String timeZone,
    Set<String> ferryIdsNotAllowedForBicycle,
    NetexProfileSupport profileSupport
  ) {
    this.issueStore = issueStore;
    this.idFactory = idFactory;
    this.agenciesById = agenciesById;
    this.operatorsById = operatorsById;
    this.brandingsById = brandingsById;
    this.groupsOfLinesByRouteId = groupsOfLinesByRouteId;
    this.groupOfRoutesById = groupOfRoutesById;
    this.netexIndex = netexIndex;
    this.authorityMapper = new AuthorityToAgencyMapper(idFactory, timeZone);
    this.ferryIdsNotAllowedForBicycle = ferryIdsNotAllowedForBicycle;
    this.profileSupport = profileSupport;
    this.transportModeMapper = new TransportModeMapper(profileSupport);
    this.lineTransportModeResolver = new LineTransportModeResolver(netexIndex);
  }

  Route mapRoute(Line_VersionStructure line) {
    MultilingualString name = line.getName() != null ? line.getName() : line.getShortName();
    if (name == null && line.getPublicCode() == null) {
      issueStore.add(
        "LineWithoutName",
        "Line %s has neither Name, ShortName nor PublicCode and is skipped.",
        line.getId()
      );
      return null;
    }
    if (line.getName() == null) {
      issueStore.add(
        "LineWithoutName",
        "Line %s has no Name, using ShortName or PublicCode instead.",
        line.getId()
      );
    }

    RouteBuilder builder = Route.of(idFactory.createId(line.getId()));

    builder.getGroupsOfRoutes().addAll(getGroupOfRoutes(line));
    builder.withAgency(findOrCreateAuthority(line));
    builder.withOperator(findOperator(line));
    builder.withBranding(findBranding(line));
    builder.withLongName(NonLocalizedString.ofNullable(name, MultilingualString::getValue));
    builder.withShortName(line.getPublicCode());

    var transportMode = line.getTransportMode();
    var transportSubmode = line.getTransportSubmode();
    // The NeTEx mode "unknown" means no information, like a missing mode
    if (
      profileSupport.extendedNetex() &&
      (transportMode == null || transportMode == AllVehicleModesOfTransportEnumeration.UNKNOWN)
    ) {
      var derived = lineTransportModeResolver.resolve(line.getId());
      if (derived != null) {
        issueStore.add(
          "LineTransportModeDerived",
          "Line %s has no or an unknown TransportMode, using %s derived from its %s.",
          line.getId(),
          derived.mode().value(),
          derived.source()
        );
        transportMode = derived.mode();
        transportSubmode = derived.submode();
      }
    }

    NetexMainAndSubMode mode;
    try {
      mode = transportModeMapper.map(transportMode, transportSubmode);
    } catch (TransportModeMapper.UnsupportedModeException e) {
      issueStore.add(
        "UnsupportedModeInLine",
        "Unsupported mode in Line. Mode: %s, line: %s",
        e.mode,
        line.getId()
      );
      return null;
    }

    builder.withMode(mode.mainMode());
    builder.withNetexSubmode(mode.subMode());

    if (line instanceof FlexibleLine_VersionStructure) {
      builder.withFlexibleLineType(
        ((FlexibleLine_VersionStructure) line).getFlexibleLineType().value()
      );
    }

    if (line.getPresentation() != null) {
      PresentationStructure presentation = line.getPresentation();
      if (presentation.getColour() != null) {
        builder.withColor(hexBinaryAdapter.marshal(presentation.getColour()));
      }
      if (presentation.getTextColour() != null) {
        builder.withTextColor(hexBinaryAdapter.marshal(presentation.getTextColour()));
      }
    }

    // we would love to read this information from the actual feed but it's unclear where
    // this information would be located.
    // the standard defines the following enum for baggage
    // https://github.com/NeTEx-CEN/NeTEx/blob/0436cb774778ae68a682c28e0a21013e4886c883/xsd/netex_part_3/part3_fares/netex_usageParameterLuggage_support.xsd#L120
    // and there is a usage of that in one of the Entur examples
    // https://github.com/entur/profile-examples/blob/7f45e036c870205102d96ef58c7ce5008f4edcf1/netex/fares-sales/Entur_PARTIAL_EXAMPLE_INCOMPLETE_FinnmarkFullExample.xml#L1598-L1611
    // but currently it doesn't look it is being parsed.
    // until there is better information from the operators we assume that all ferries allow
    // bicycles on board.
    if (isFerry(transportMode)) {
      if (ferryIdsNotAllowedForBicycle.contains(line.getId())) {
        builder.withBikesAllowed(BikeAccess.NOT_ALLOWED);
      } else {
        builder.withBikesAllowed(BikeAccess.ALLOWED);
      }
    }

    return builder.build();
  }

  /**
   * The Nordic profile uses the mode water for ferries; EPIP also has the mode ferry, which is
   * supported with extended NeTEx support.
   */
  private boolean isFerry(@Nullable AllVehicleModesOfTransportEnumeration mode) {
    return (
      mode == AllVehicleModesOfTransportEnumeration.WATER ||
      (profileSupport.extendedNetex() && mode == AllVehicleModesOfTransportEnumeration.FERRY)
    );
  }

  private Collection<GroupOfRoutes> getGroupOfRoutes(Line_VersionStructure line) {
    // Relation can be set both on Line or on GroupOfLines
    // Look on both indexes so that nothing is omitted

    FeedScopedId lineId = idFactory.createId(line.getId());
    // Use set in case ref is set on both sides
    Set<GroupOfRoutes> groupsOfRoutes = new HashSet<>(groupsOfLinesByRouteId.get(lineId));

    GroupOfLinesRefStructure representedByGroupRef = line.getRepresentedByGroupRef();

    if (representedByGroupRef != null) {
      FeedScopedId groupOfRoutesId = idFactory.createId(representedByGroupRef.getRef());
      if (this.groupOfRoutesById.containsKey(groupOfRoutesId)) {
        groupsOfRoutes.add(groupOfRoutesById.get(groupOfRoutesId));
      }
    }

    return groupsOfRoutes;
  }

  /**
   * Find an agency by mapping the GroupOfLines/Network Authority. If no authority is found a dummy
   * agency is created and returned.
   */
  private Agency findOrCreateAuthority(Line_VersionStructure line) {
    GroupOfLinesRefStructure representedByGroupRef = line.getRepresentedByGroupRef();
    Agency agency = null;
    if (representedByGroupRef != null) {
      String groupRef = representedByGroupRef.getRef();

      // Find authority, first in *GroupOfLines* and then if not found look in *Network*
      Network network = netexIndex.lookupNetworkForLine(groupRef);

      if (network != null && network.getTransportOrganisationRef() != null) {
        String orgRef = network.getTransportOrganisationRef().getValue().getRef();
        agency = agenciesById.get(idFactory.createId(orgRef));
      }
    } else if (line.getAuthorityRef() != null) {
      agency = agenciesById.get(idFactory.createId(line.getAuthorityRef().getRef()));
    }
    if (agency == null && profileSupport.extendedNetex()) {
      agency = findAgencyWithExtendedNetex(line);
    }
    // No authority found in Network or GroupOfLines.
    // Use the dummy agency, create if necessary
    return agency != null ? agency : createOrGetDummyAgency(line);
  }

  /**
   * In EPIP a line references either its operator or its authority, and additional transport
   * organisations may be operators or authorities (CEN/TS 16614-4, 7.1.1). VDV 462 has no
   * authority at all. Look for an authority in all these places, and use the operator of the line
   * as agency if there is no authority.
   */
  @Nullable
  private Agency findAgencyWithExtendedNetex(Line_VersionStructure line) {
    if (line.getAuthorityRef() != null) {
      var agency = agenciesById.get(idFactory.createId(line.getAuthorityRef().getRef()));
      if (agency != null) {
        return agency;
      }
    }
    if (line.getAdditionalOperators() != null) {
      for (var ref : line.getAdditionalOperators().getTransportOrganisationRef()) {
        var agency = agenciesById.get(idFactory.createId(ref.getValue().getRef()));
        if (agency != null) {
          return agency;
        }
      }
    }
    var operator = findOperator(line);
    if (operator == null) {
      return null;
    }
    issueStore.add(
      "AgencyDerivedFromOperator",
      "Line %s has no authority, the operator %s is used as agency.",
      line.getId(),
      operator.getId()
    );
    var agency = agenciesById.get(operator.getId());
    if (agency == null) {
      agency = authorityMapper.mapOperatorToAgency(operator);
      agenciesById.add(agency);
    }
    return agency;
  }

  private Agency createOrGetDummyAgency(Line_VersionStructure line) {
    issueStore.add("LineWithoutAuthority", "No authority found for %s", line.getId());

    Agency agency = agenciesById.get(idFactory.createId(authorityMapper.dummyAgencyId()));

    if (agency == null) {
      agency = authorityMapper.createDummyAgency();
      agenciesById.add(agency);
    }
    return agency;
  }

  @Nullable
  private Operator findOperator(Line_VersionStructure line) {
    OperatorRefStructure opeRef = line.getOperatorRef();

    if (opeRef != null) {
      return operatorsById.get(idFactory.createId(opeRef.getRef()));
    }
    return profileSupport.extendedNetex() ? findAdditionalOperator(line) : null;
  }

  /**
   * EPIP and VDV 462 data often state the operator of a line only as an additional operator (the
   * line then references its authority). Use the first additional operator which is an operator.
   */
  @Nullable
  private Operator findAdditionalOperator(Line_VersionStructure line) {
    if (line.getAdditionalOperators() == null) {
      return null;
    }
    List<Operator> operators = line
      .getAdditionalOperators()
      .getTransportOrganisationRef()
      .stream()
      .map(ref -> operatorsById.get(idFactory.createId(ref.getValue().getRef())))
      .filter(Objects::nonNull)
      .distinct()
      .toList();
    if (operators.isEmpty()) {
      return null;
    }
    if (operators.size() > 1) {
      issueStore.add(
        "LineWithSeveralAdditionalOperators",
        "Line %s has no OperatorRef but several additional operators, using %s.",
        line.getId(),
        operators.getFirst().getId()
      );
    }
    return operators.getFirst();
  }

  @Nullable
  private Branding findBranding(Line_VersionStructure line) {
    BrandingRefStructure brandingRef = line.getBrandingRef();

    if (brandingRef == null) {
      return null;
    }

    return brandingsById.get(idFactory.createId(brandingRef.getRef()));
  }
}
