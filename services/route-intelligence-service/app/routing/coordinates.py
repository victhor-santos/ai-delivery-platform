from app.routing.graph import GraphModel, Latitude, Longitude


class GeoPoint(GraphModel):
    lat: Latitude
    lon: Longitude
