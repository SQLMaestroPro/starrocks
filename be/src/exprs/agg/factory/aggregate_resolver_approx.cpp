// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "exprs/agg/aggregate.h"
#include "exprs/agg/aggregate_factory.h"
#include "exprs/agg/factory/aggregate_factory.hpp"
#include "exprs/agg/factory/aggregate_resolver.hpp"
#include "types/hll.h"
#include "types/logical_type.h"

namespace starrocks {

struct HLLUnionBuilder {
    template <LogicalType pt>
    void operator()(AggregateFuncResolver* resolver) {
        if constexpr (pt_is_fixedlength<pt> || pt_is_string<pt>) {
            resolver->add_aggregate_mapping<pt, TYPE_HLL, HyperLogLog>(
                    "hll_raw", false, AggregateFactory::MakeHllRawAggregateFunction<pt>());

            using IntersectCountState = BitmapIntersectAggregateState<BitmapRuntimeCppType<pt>>;
            resolver->add_aggregate_mapping_variadic<pt, TYPE_BIGINT, IntersectCountState>(
                    "intersect_count", false, AggregateFactory::MakeIntersectCountAggregateFunction<pt>());

            resolver->add_aggregate_mapping<pt, TYPE_BIGINT, HyperLogLog>(
                    "ndv", false, AggregateFactory::MakeHllNdvAggregateFunction<pt>());

            resolver->add_aggregate_mapping<pt, TYPE_BIGINT, HyperLogLog>(
                    "approx_count_distinct", false, AggregateFactory::MakeHllNdvAggregateFunction<pt>());
        }
    }
};

void AggregateFuncResolver::register_approx() {
    for (auto type : aggregate_types()) {
        type_dispatch_all(type, HLLUnionBuilder(), this);
    }
    add_aggregate_mapping<TYPE_HLL, TYPE_HLL, HyperLogLog>("hll_union", false,
                                                           AggregateFactory::MakeHllUnionAggregateFunction());
    add_aggregate_mapping<TYPE_HLL, TYPE_HLL, HyperLogLog>("hll_raw_agg", false,
                                                           AggregateFactory::MakeHllUnionAggregateFunction());
    add_aggregate_mapping<TYPE_HLL, TYPE_BIGINT, HyperLogLog>("hll_union_agg", false,
                                                              AggregateFactory::MakeHllUnionCountAggregateFunction());
}

} // namespace starrocks
