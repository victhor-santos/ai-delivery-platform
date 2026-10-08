import { Route, Routes } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'
import { RequireAuth } from './auth/RequireAuth'
import { Layout } from './components/Layout'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OrderPage } from './pages/OrderPage'
import { RegisterPage } from './pages/RegisterPage'
import { RestaurantPage } from './pages/RestaurantPage'
import { RestaurantsPage } from './pages/RestaurantsPage'

export function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route
            index
            element={
              <RequireAuth>
                <HomePage />
              </RequireAuth>
            }
          />
          <Route
            path="restaurants"
            element={
              <RequireAuth>
                <RestaurantsPage />
              </RequireAuth>
            }
          />
          <Route
            path="restaurants/:restaurantId"
            element={
              <RequireAuth>
                <RestaurantPage />
              </RequireAuth>
            }
          />
          <Route
            path="orders/:orderId"
            element={
              <RequireAuth>
                <OrderPage />
              </RequireAuth>
            }
          />
          <Route path="login" element={<LoginPage />} />
          <Route path="register" element={<RegisterPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </AuthProvider>
  )
}
