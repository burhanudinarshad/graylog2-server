import React from 'react';
import PropTypes from 'prop-types';
import createReactClass from 'create-react-class';
import Immutable from 'immutable';

import { Col } from 'react-bootstrap';

import { Spinner } from 'components/common';
import DashboardWidgetGrid from 'enterprise/components/dashboard/DashboardWidgetGrid';
import { widgetDefinition } from 'enterprise/logic/Widget';
import ViewsActions from 'enterprise/actions/ViewsActions';
import DashboardWidgetsActions from 'enterprise/actions/DashboardWidgetsActions';
import EmptyDashboardHelpMessage from './EmptyDashboardHelpMessage';

const DashboardContainer = createReactClass({
  propTypes: {
    view: PropTypes.instanceOf(Immutable.Map).isRequired,
    widgets: PropTypes.instanceOf(Immutable.Map).isRequired,
    dashboardWidgets: PropTypes.instanceOf(Immutable.Map).isRequired,
    widgetMapping: PropTypes.object,
    results: PropTypes.object,
  },

  getDefaultProps() {
    return {
      widgetMapping: {},
      results: {},
    };
  },

  getInitialState() {
    return {};
  },

  updatePositions(view, newPositions) {
    const updatedView = view.set('dashboardPositions', newPositions);
    ViewsActions.update(updatedView.get('id'), updatedView);
  },

  handlePositionsChange(positions, view) {
    const newPositions = {};
    positions.forEach(({ col, height, row, width, id }) => {
      newPositions[id] = { col, height, row, width };
    });
    this.updatePositions(view, newPositions);
  },

  handleWidgetDelete(view, widgetId) {
    DashboardWidgetsActions.removeFromDashboard(view.get('id'), widgetId);

    const positions = view.get('dashboardPositions', {});
    delete positions[widgetId];
    this.updatePositions(view, positions);
  },

  renderWidgetGrid(widgetDefs, dashboardWidgets, widgetMapping, queryResults, view) {
    const widgets = {};
    const data = {};
    let fields = new Immutable.Map();

    const viewDashboardWidgets = dashboardWidgets.get(view.get('id'));

    if (!viewDashboardWidgets) {
      // No dashboard widgets defined
      return <EmptyDashboardHelpMessage />;
    }

    const widgetsWithResults = viewDashboardWidgets.map((value, widgetId) => {
      let m = new Immutable.Map();
      m = m.set('widget', widgetDefs.getIn([value.queryId, widgetId]));
      m = m.set('result', new Immutable.Map(queryResults[value.queryId]));
      return m;
    });

    widgetsWithResults.valueSeq().forEach((widgetWithResult) => {
      const widgetDef = widgetWithResult.get('widget');
      const result = widgetWithResult.get('result');
      const searchTypes = result.get('searchTypes');

      const widgetType = widgetDefinition(widgetDef.type);
      const dataTransformer = widgetType.searchResultTransformer || (x => x);
      const widgetData = (widgetMapping[widgetDef.id] || []).map(searchTypeId => searchTypes[searchTypeId]);
      if (widgetData) {
        widgets[widgetDef.id] = widgetDef;
        data[widgetDef.id] = dataTransformer(widgetData, widgetDef.toJSON());
        if (widgetDef.type === 'messages' && widgetData.fields) {
          fields = new Immutable.Map(widgetData.fields);
        }
      }
    });
    const positions = view.get('dashboardPositions');
    return (
      <DashboardWidgetGrid fields={fields}
                           viewId={view.get('id')}
                           locked={false}
                           widgets={widgets}
                           positions={positions}
                           data={data}
                           onWidgetDelete={widget => this.handleWidgetDelete(view, widget)}
                           onPositionsChange={p => this.handlePositionsChange(p, view)} />
    );
  },

  render() {
    const { widgets, dashboardWidgets, widgetMapping, results, view } = this.props;

    if (!results.results) {
      return <Col md={12}><Spinner /></Col>;
    }

    const widgetGrid = this.renderWidgetGrid(widgets, dashboardWidgets, widgetMapping, results.results, view);

    return (
      <Col md={12}>
        {widgetGrid}
      </Col>
    );
  },
});

export default DashboardContainer;
