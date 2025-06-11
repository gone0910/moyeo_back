package com.example.capstone.matching.repository;

import com.example.capstone.matching.entity.*;
import com.example.capstone.user.entity.Gender;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static com.example.capstone.matching.entity.QMatchCity.matchCity;
import static com.example.capstone.matching.entity.QMatchTravelStyle.matchTravelStyle;
import static com.example.capstone.matching.entity.QMatchingProfile.matchingProfile;
import static com.example.capstone.user.entity.QUserEntity.userEntity;

@Slf4j
@RequiredArgsConstructor
public class MatchingProfileRepositoryCustomImpl implements MatchingProfileRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<MatchingProfile> matchingProfile(MatchingProfile profile) {
        return queryFactory
                .select(matchingProfile)
                .from(matchingProfile)
                .join(matchingProfile.user, userEntity).fetchJoin()
                .leftJoin(matchingProfile.travelStyles, matchTravelStyle).fetchJoin()
                .leftJoin(matchingProfile.matchCities, matchCity)
                .where(
                        notSelf(profile.getId()),
                        dateBetween(profile.getStartDate(), profile.getEndDate()),
                        provinceEq(profile.getProvince()),
                        cityEq(profile.getMatchCities()),
                        groupTypeEq(profile.getGroupType()),
                        ageRangeEq(profile.getAgeRange()),
                        travelStyleEq(profile.getTravelStyles()),
                        preferenceGenderEq(profile.getPreferenceGender())
                )
                .fetch();
    }


    private BooleanExpression notSelf(Long profileId) {
        return matchingProfile.id.ne(profileId);
    }

    private BooleanExpression dateBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {throw new RuntimeException("input start, end Date");}
        return matchingProfile.startDate.loe(endDate).and(matchingProfile.endDate.goe(startDate));
    }

    private BooleanExpression provinceEq(Province province) {
        if (province != null && province != Province.NONE) {
            return matchingProfile.province.eq(province)
                    .or(matchingProfile.province.eq(Province.NONE));
        }
        return null;
    }

    private BooleanExpression groupTypeEq(GroupType groupType) {
        if (groupType != null && groupType != GroupType.NONE) {
            return matchingProfile.groupType.eq(groupType)
                    .or(matchingProfile.groupType.eq(GroupType.NONE));
        }
        return null;
    }

    private BooleanExpression ageRangeEq(Integer ageRange) {
        if (ageRange == null || ageRange == 0) {
            return null;
        }

        BooleanExpression ageCondition;

        switch (ageRange) {
            case 10:
                ageCondition = userEntity.age.between(10, 19);
                break;
            case 20:
                ageCondition = userEntity.age.between(20, 29);
                break;
            case 30:
                ageCondition = userEntity.age.between(30, 39);
                break;
            case 40:
                ageCondition = userEntity.age.between(40, 49);
                break;
            case 50:
                ageCondition = userEntity.age.between(50, 59);
                break;
            case 60:
                ageCondition = userEntity.age.goe(60);
                break;
            default:
                return null;
        }

        return ageCondition.or(userEntity.age.isNull());
    }

    private BooleanExpression travelStyleEq(List<MatchTravelStyle> travelStyles) {
        if (travelStyles == null || travelStyles.isEmpty()) {
            return null;
        }

        List<TravelStyle> styles = travelStyles.stream()
                .map(MatchTravelStyle::getTravelStyle)
                .filter(style -> style != null && style != TravelStyle.NONE)
                .collect(Collectors.toList());

        return styles.isEmpty() ?
                null :
                matchTravelStyle.travelStyle.in(styles)
                        .or(matchTravelStyle.travelStyle.eq(TravelStyle.NONE));
    }

    private BooleanExpression cityEq(List<MatchCity> cities) {
        if (cities == null || cities.isEmpty()) {
            return null;
        }

        List<City> cityList = cities.stream()
                .map(MatchCity::getCity)
                .filter(city -> city != null && city != City.NONE)
                .collect(Collectors.toList());

        return cityList.isEmpty() ?
                null :
                matchCity.city.in(cityList)
                        .or(matchCity.city.eq(City.NONE));
    }

    private BooleanExpression preferenceGenderEq(PreferenceGender preferenceGender) {
        Gender gender = (preferenceGender != null && preferenceGender != PreferenceGender.NONE) ? preferenceGender.toGender() : null;
        return (gender != null) ? userEntity.gender.eq(gender) : null;
    }
}
