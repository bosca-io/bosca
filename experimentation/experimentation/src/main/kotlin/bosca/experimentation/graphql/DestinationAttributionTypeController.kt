package bosca.experimentation.graphql

import bosca.experimentation.model.*
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Explicit fields for persisted DestinationAttribution aggregates. */
@TypeController(type = "DestinationAttribution")
class DestinationAttributionTypeController : GraphQLController<DestinationAttribution> {
    @Field fun engagedSubjects(value: DestinationAttribution): Long = value.engagedSubjects
    @Field fun interactions(value: DestinationAttribution): Long = value.interactions
    @Field fun attributedSubjects(value: DestinationAttribution): Long = value.attributedSubjects
    @Field fun attributedInteractions(value: DestinationAttribution): Long = value.attributedInteractions
    @Field fun interval(value: DestinationAttribution): MetricInterval? = value.interval
    @Field fun unresolvedTargets(value: DestinationAttribution): Long = value.unresolvedTargets
    @Field fun unsupportedTypes(value: DestinationAttribution): Long = value.unsupportedTypes
    @Field fun missingSlugs(value: DestinationAttribution): Long = value.missingSlugs
    @Field fun missingSessions(value: DestinationAttribution): Long = value.missingSessions
    @Field fun unmatchedDestinations(value: DestinationAttribution): Long = value.unmatchedDestinations
}
